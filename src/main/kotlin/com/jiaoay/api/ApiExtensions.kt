package com.jiaoay.api

import retrofit2.Retrofit
import kotlin.reflect.KClass

fun <R : Any> Retrofit.create(serviceClass: KClass<R>): R {
    return create(serviceClass.java)
}

fun <R> Retrofit.create(serviceClass: Class<R>): R {
    return create(serviceClass)
}
