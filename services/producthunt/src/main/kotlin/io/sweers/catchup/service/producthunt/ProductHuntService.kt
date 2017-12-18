/*
 * Copyright (c) 2017 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sweers.catchup.service.producthunt

import com.serjltt.moshi.adapters.Wrapped
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import io.reactivex.Maybe
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.DataRequest
import io.sweers.catchup.service.api.DataResult
import io.sweers.catchup.service.api.LinkHandler
import io.sweers.catchup.service.api.Service
import io.sweers.catchup.service.api.ServiceKey
import io.sweers.catchup.service.api.ServiceMeta
import io.sweers.catchup.service.api.ServiceMetaKey
import io.sweers.catchup.service.api.TextService
import io.sweers.catchup.util.data.adapters.ISO8601InstantAdapter
import io.sweers.catchup.util.network.AuthInterceptor
import okhttp3.OkHttpClient
import org.threeten.bp.Instant
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
private annotation class InternalApi

internal class ProductHuntService @Inject constructor(
    @InternalApi private val serviceMeta: ServiceMeta,
    private val api: ProductHuntApi,
    private val urlHunter: UrlHunter,
    private val linkHandler: LinkHandler)
  : TextService {

  override fun meta() = serviceMeta

  override fun fetchPage(request: DataRequest): Maybe<DataResult> {
    val page = request.pageId.toInt()
    return api.getPosts(page)
        .flattenAsObservable { it }
        .concatMapEager { post ->
          urlHunter.hunt(post.redirectUrl())
              .map {
                post to it.response()?.raw()?.request()?.url()?.host()
              }
              .toObservable()
        }
        .map { (post, host) ->
          with(post) {
            CatchUpItem(
                id = id(),
                title = name(),
                score = "▲" to votesCount(),
                timestamp = createdAt(),
                author = user().name(),
                tag = firstTopic,
                source = host,
                commentCount = commentsCount(),
                itemClickUrl = redirectUrl(),
                itemCommentClickUrl = discussionUrl()
            )
          }
        }
        .toList()
        .map { DataResult(it, (page + 1).toString()) }
        .toMaybe()
  }

  override fun linkHandler() = linkHandler
}

@Module
abstract class ProductHuntModule {

  @IntoMap
  @ServiceMetaKey(SERVICE_KEY)
  @Binds
  internal abstract fun productHuntServiceMeta(@InternalApi meta: ServiceMeta): ServiceMeta

  @IntoMap
  @ServiceKey(SERVICE_KEY)
  @Binds
  internal abstract fun productHuntService(productHuntService: ProductHuntService): Service

  @Module
  companion object {

    private const val SERVICE_KEY = "ph"

    @InternalApi
    @Provides
    @JvmStatic
    internal fun provideProductHuntServiceMeta() = ServiceMeta(
        SERVICE_KEY,
        R.string.ph,
        R.color.phAccent,
        R.drawable.logo_ph,
        pagesAreNumeric = true,
        firstPageKey = "0"
    )

    @Provides
    @InternalApi
    @JvmStatic
    internal fun provideProductHuntOkHttpClient(
        client: OkHttpClient): OkHttpClient {
      return client.newBuilder()
          .addInterceptor(AuthInterceptor("Bearer",
              BuildConfig.PROCUCT_HUNT_DEVELOPER_TOKEN))
          .build()
    }

    @Provides
    @InternalApi
    @JvmStatic
    internal fun provideProductHuntMoshi(moshi: Moshi): Moshi {
      return moshi.newBuilder()
          .add(ProductHuntAdapterFactory.create())
          .add(Instant::class.java, ISO8601InstantAdapter())
          .add(Wrapped.ADAPTER_FACTORY)
          .build()
    }

    @Provides
    @JvmStatic
    internal fun provideProductHuntService(
        @InternalApi client: Lazy<OkHttpClient>,
        @InternalApi moshi: Moshi,
        rxJavaCallAdapterFactory: RxJava2CallAdapterFactory): ProductHuntApi {
      return Retrofit.Builder().baseUrl(ProductHuntApi.ENDPOINT)
          .callFactory { client.get().newCall(it) }
          .addCallAdapterFactory(rxJavaCallAdapterFactory)
          .addConverterFactory(MoshiConverterFactory.create(moshi))
          .validateEagerly(BuildConfig.DEBUG)
          .build()
          .create(ProductHuntApi::class.java)
    }

    @Provides
    @JvmStatic
    internal fun provideUrlHunter(
        client: Lazy<OkHttpClient>,
        rxJavaCallAdapterFactory: RxJava2CallAdapterFactory): UrlHunter {
      return Retrofit.Builder().baseUrl(UrlHunter.ENDPOINT)
          .callFactory { client.get().newCall(it) }
          .addCallAdapterFactory(rxJavaCallAdapterFactory)
          .validateEagerly(BuildConfig.DEBUG)
          .build()
          .create(UrlHunter::class.java)
    }
  }
}
