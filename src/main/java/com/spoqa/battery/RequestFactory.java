/**
 * Copyright (c) 2014 Park Joon-Kyu, All Rights Reserved.
 */

package com.spoqa.battery;

import com.spoqa.battery.annotations.QueryString;
import com.spoqa.battery.annotations.RpcObject;
import com.spoqa.battery.annotations.UriFragment;
import com.spoqa.battery.codecs.UrlEncodedFormEncoder;
import com.spoqa.battery.exceptions.ContextException;
import com.spoqa.battery.exceptions.SerializationException;
import com.spoqa.battery.transformers.CamelCaseTransformer;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RequestFactory {
    private static final String TAG = "RequestFactory";

    public static HttpRequest createRequest(ExecutionContext context, Object object)
            throws SerializationException, ContextException {
        /* validate current preprocessor context (if exists) */
        if (context.getRequestPreprocessor() != null) {
            Throwable error = context.getRequestPreprocessor().validateContext(object);
            if (error != null)
                throw new ContextException(error);
        }

        RpcObject annotation = object.getClass().getAnnotation(RpcObject.class);
        if (annotation == null) {
            Logger.error(TAG, String.format("Attempted to create a request from non-RpcObject"));
            return null;
        }

        FieldNameTransformer remote, local;
        local = context.getLocalFieldNameTransformer();
        remote = context.getRemoteFieldNameTransformer();
        try {
            if (annotation.remoteName() != RpcObject.NULL.class)
                remote = (FieldNameTransformer) annotation.remoteName().newInstance();
            if (annotation.localName() != RpcObject.NULL.class)
                local = (FieldNameTransformer) annotation.localName().newInstance();
        } catch (InstantiationException e) {
            e.printStackTrace();
            Logger.error(TAG, "Failed to create request.");
            return null;
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            Logger.error(TAG, "Failed to create request.");
            return null;
        }

        FieldNameTranslator nameTranslator = new FieldNameTranslator(remote, local);

        Map<String, Object> parameters = new HashMap<String, Object>();
        int method = annotation.method();
        String uri = buildUri(context, object, annotation, parameters, nameTranslator);
        if (uri == null) {
            Logger.error(TAG, "Failed to create request.");
            return null;
        }

        HttpRequest request = new HttpRequest(method, uri);
        request.setNameTranslator(nameTranslator);
        request.putParameters(parameters);
        request.setRequestObject(object);

        /* set request body */
        Class serializerCls = annotation.requestSerializer();
        if (method == HttpRequest.Methods.POST || method == HttpRequest.Methods.PUT) {
            if (serializerCls == RpcObject.NULL.class && context.getRequestSerializer() == null)
                serializerCls = UrlEncodedFormEncoder.class;

            if (serializerCls != RpcObject.NULL.class) {
                try {
                    RequestSerializer serializer = (RequestSerializer) serializerCls.newInstance();
                    request.putHeader(HttpRequest.HEADER_CONTENT_TYPE, serializer.serializationContentType());
                    request.setRequestBody(serializer.serializeObject(object, nameTranslator));
                } catch (InstantiationException e) {
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            } else if (context.getRequestSerializer() != null) {
                RequestSerializer serializer = context.getRequestSerializer();
                request.putHeader(HttpRequest.HEADER_CONTENT_TYPE, serializer.serializationContentType());
                request.setRequestBody(serializer.serializeObject(object, nameTranslator));
            } else {
                Logger.warn(TAG, String.format("Current RpcObject %1$s does not have " +
                                "RequestSerializer specified.", object.getClass().getName()));
            }
        }

        /* apply custom request processing middleware */
        if (context.getRequestPreprocessor() != null)
            context.getRequestPreprocessor().processHttpRequest(request);

        return request;
    }

    private static String buildUri(ExecutionContext context, Object object, RpcObject rpcObjectDecl,
                                   Map<String, Object> params, FieldNameTranslator translator) {
        String uri = rpcObjectDecl.uri();
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
            if (context.getDefaultUriPrefix() == null) {
                Logger.error(TAG, String.format("No URI prefix given."));
                return null;
            }
            if (uri.startsWith("/"))
                uri = uri.substring(1);
            uri = context.getDefaultUriPrefix() + "/" + uri;
        }

        /* Build REST URI fragment */
        Class self = object.getClass();
        List<Field> uriFragments = CodecUtils.getAnnotatedFields(null, UriFragment.class, self);
        if (uriFragments != null && uriFragments.size() > 0) {
            Map<Integer, Field> fieldMap = new HashMap<Integer, Field>();
            for (Field f : uriFragments) {
                UriFragment uf = (UriFragment) f.getAnnotation(UriFragment.class);
                fieldMap.put(uf.value(), f);
            }

            List<Field> fieldList = new ArrayList<Field>();
            for (int i = 1; i <= fieldMap.size(); ++i) {
                if (!fieldMap.containsKey(i)) {
                    Logger.error(TAG, String.format("Positional argument %1$d not found in %2$s", i,
                            self.getName()));
                    return null;
                } else {
                    fieldList.add(fieldMap.get(i));
                }
            }

            Object[] parameters = new Object[fieldList.size()];
            int i = 0;
            for (Field field : fieldList) {
                String paramName = field.getName();
                try {
                    Object fieldObject = field.get(object);
                    if (fieldObject == null ||
                            (!CodecUtils.isPrimitive(fieldObject.getClass()) &&
                             !CodecUtils.isString(fieldObject))) {
                        Logger.error(TAG, String.format("Type '%1$s' of field '%2$s' could not be built into URI.",
                                fieldObject.getClass().getName(), paramName));
                        return null;
                    }

                    if (fieldObject instanceof String) {
                        try {
                            parameters[i] = URLEncoder.encode((String) fieldObject, "utf-8");
                        } catch (UnsupportedEncodingException e) {}
                    } else {
                        parameters[i] = fieldObject;
                    }
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                    ++i;
                    continue;
                }
                ++i;
            }

            uri = String.format(uri, parameters);
        }

        /* append query string */
        List<Field> queryStringParams = CodecUtils.getAnnotatedFields(null, QueryString.class, object.getClass());
        for (Field field : queryStringParams) {
            String fieldName = field.getName();
            Class fieldType = field.getType();

            if (!CodecUtils.isString(fieldType) &&
                    !CodecUtils.isInteger(fieldType) &&
                    !CodecUtils.isBoolean(fieldType) &&
                    !CodecUtils.isFloat(fieldType) &&
                    !CodecUtils.isDouble(fieldType) &&
                    !CodecUtils.isLong(fieldType)) {
                Logger.error(TAG, String.format("Type '%1$s' of field '%2$s' could not be built into URI.",
                        field.getClass().getName(), field.getName()));
                continue;
            }

            /* override field name if optional value is supplied */
            QueryString annotation = field.getAnnotation(QueryString.class);
            if (annotation.fieldName().length() > 0)
                fieldName = annotation.fieldName();
            else
                fieldName = translator.localToRemote(fieldName);

            try {
                params.put(fieldName, field.get(object));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }

        return uri;
    }
}
