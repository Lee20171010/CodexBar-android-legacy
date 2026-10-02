package com.codexbar.android.core.network.opencode

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers

interface OpenCodeApiService {
    @Headers("Accept: application/json")
    @GET("zen/go/v1/usage")
    suspend fun getUsage(
        @Header("Authorization") authorization: String
    ): Response<OpenCodeDto.UsageEnvelope>
}
