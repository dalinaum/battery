/**
 * Copyright (c) 2014 Park Joon-Kyu, All Rights Reserved.
 */

package com.spoqa.battery.annotations;

import com.spoqa.battery.HttpRequest;
import com.spoqa.battery.codecs.UrlEncodedFormEncoder;
import com.spoqa.battery.transformers.DefaultTransformer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to specify callee's HTTP request metadata
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RpcObject {
    static final class NULL {}

    public int method() default HttpRequest.Methods.GET;
    public String uri();
    public String[] uriParams() default {};
    public Class requestSerializer() default UrlEncodedFormEncoder.class;
    public Class nameTransformer() default DefaultTransformer.class;
    public String expectedContentType() default "";
}
