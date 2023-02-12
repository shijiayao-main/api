package com.jiaoay.api

import com.jiaoay.api.moshi.ConcurrentHashMapJsonAdapter
import com.jiaoay.api.moshi.HashMapJsonAdapter
import com.jiaoay.api.moshi.JSONAdapter
import com.jiaoay.api.moshi.LinkedHashMapJsonAdapter
import com.jiaoay.api.moshi.NullSafeKotlinJsonAdapterFactory
import com.jiaoay.api.moshi.NullSafeStandardJsonAdapters
import com.jiaoay.common.logger.CommonLog
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.brotli.BrotliInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object ApiEngineManager {

    private const val TAG = "ApiEngineManager"

    private val retrofitMap: MutableMap<String, Retrofit> = HashMap()

    private val defaultDispatcher: Dispatcher by lazy {
        val threadFactory = ThreadFactory { runnable ->
            val result = Thread(runnable, "OkHttp-Api")
            result.isDaemon = false
            result
        }
        val executorService: ExecutorService = ThreadPoolExecutor(
            0,
            Int.MAX_VALUE,
            60,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            threadFactory,
        )
        val dispatcher = Dispatcher(executorService)
        dispatcher.maxRequestsPerHost = 15
        dispatcher
    }

    /**
     * @param host host
     * @param tag tag
     * @param dispatcher
     * @param builderInit you can add your interceptor in it,
     *                    it will be invoke before build okhttp client
     */
    fun createRetrofit(
        host: String?,
        tag: String,
        dispatcher: Dispatcher? = defaultDispatcher,
        builderInit: OkHttpClient.Builder.() -> Unit = {},
    ): Retrofit {
        synchronized(retrofitMap) {
            val key = getKey(tag = tag, host = host)
            retrofitMap.get(key = key)?.let {
                CommonLog.d(TAG, "already create: $tag, get from cache.")
                return it
            }

            CommonLog.d(TAG, "cache not found: $tag, start create.")
            val retrofit = buildRetrofit(
                host = host,
                dispatcher = dispatcher,
                builderInit = builderInit,
            )
            retrofitMap[key] = retrofit
            return retrofit
        }
    }

    fun recycle() {
        synchronized(retrofitMap) {
            CommonLog.d(TAG, "recycle")
            retrofitMap.clear()
            defaultDispatcher.cancelAll()
        }
    }

    private fun getKey(tag: String, host: String?): String {
        if (host == null) {
            return tag
        }
        return "$tag-${host.hashCode()}"
    }

    private fun buildRetrofit(
        host: String?,
        dispatcher: Dispatcher?,
        builderInit: OkHttpClient.Builder.() -> Unit,
    ): Retrofit {
        val httpClient = buildHttpClient(
            builderInit = builderInit,
            dispatcher = dispatcher,
        ).build()

        val moshi: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .add(JSONAdapter.FACTORY)
            .add(HashMapJsonAdapter.FACTORY)
            .add(ConcurrentHashMapJsonAdapter.FACTORY)
            .add(LinkedHashMapJsonAdapter.FACTORY)
            .add(NullSafeStandardJsonAdapters.FACTORY)
            .addLast(NullSafeKotlinJsonAdapterFactory())
            .build()

        var builder: Retrofit.Builder = Retrofit.Builder()
        if (host != null) {
            builder = builder.baseUrl(host)
        }
        builder = builder.addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(httpClient)

        return builder.build()
    }

    private fun buildHttpClient(
        builderInit: OkHttpClient.Builder.() -> Unit,
        dispatcher: Dispatcher?,
    ): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
        if (dispatcher != null) {
            builder.dispatcher(dispatcher)
        }

        builderInit.invoke(builder)
        builder.addInterceptor(BrotliInterceptor)

        val cs = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .allEnabledCipherSuites()
            .allEnabledTlsVersions()
            .build()

        builder.connectionSpecs(listOf(ConnectionSpec.CLEARTEXT, cs))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        return builder
    }
}
