/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jamesward.cloudbuildnotifier

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.Version
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier
import com.fasterxml.jackson.databind.deser.ResolvableDeserializer
import com.google.cloud.errorreporting.v1beta1.ErrorGroupServiceClient
import com.google.cloud.errorreporting.v1beta1.ErrorStatsServiceClient
import com.google.cloud.errorreporting.v1beta1.ReportErrorsServiceClient
import com.google.devtools.clouderrorreporting.v1beta1.*
import com.google.protobuf.util.Timestamps
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Error
import io.micronaut.http.annotation.Post
import io.micronaut.http.hateoas.JsonError
import io.micronaut.http.hateoas.Link
import io.micronaut.runtime.Micronaut.build
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

fun main(args: Array<String>) {
    build()
        .args(*args)
        .packages("com.jamesward.cloudbuildnotifier")
        .start()
}

@Controller
class Server(@Inject val buildMetrics: BuildMetrics) {

    private val LOG = LoggerFactory.getLogger(Server::class.java)

    @Error(global = true)
    fun error(request: HttpRequest<*>, e: Throwable): HttpResponse<JsonError> {
        val error = JsonError(e.message).link(Link.SELF, Link.of(request.uri))
        //LOG.error("Error handling $request", e)
        LOG.error("Error handling $request with body ${request.body}", e)

        return HttpResponse.badRequest<JsonError>().body(error)
    }

    @Post("/")
    suspend fun index(@Body envelope: Envelope) = run {
        println(envelope)
        buildMetrics.send(envelope.message.data)

        HttpResponse.noContent<Unit>()
    }

}

@Singleton
class MyModule : Module() {
    override fun getModuleName(): String {
        return javaClass.canonicalName
    }

    override fun version(): Version {
        return Version.unknownVersion()
    }

    override fun setupModule(context: SetupContext?) {
        context?.addBeanDeserializerModifier(MyBeanDeserializerModifier())
    }

}

class MyBeanDeserializerModifier : BeanDeserializerModifier() {

    override fun modifyDeserializer(config: DeserializationConfig?, beanDesc: BeanDescription?, deserializer: JsonDeserializer<*>?): JsonDeserializer<*> {
        if (beanDesc?.beanClass == Build::class.java) {
            return object : JsonDeserializer<Build>(), ResolvableDeserializer {
                override fun deserialize(p: JsonParser?, ctxt: DeserializationContext?): Build? {
                    return if (p?.currentToken == JsonToken.VALUE_STRING) {
                        val bytes = p.readValueAs(ByteArray::class.java)
                        val om = p.codec as ObjectMapper
                        om.readValue(bytes, Build::class.java)
                    } else {
                        deserializer?.deserialize(p, ctxt) as Build
                    }
                }

                override fun resolve(ctxt: DeserializationContext?) {
                    (deserializer as ResolvableDeserializer).resolve(ctxt)
                }
            }
        }

        return super.modifyDeserializer(config, beanDesc, deserializer)
    }
}

enum class BuildStatus {
     STATUS_UNKNOWN, QUEUED, WORKING, SUCCESS, FAILURE, INTERNAL_ERROR, TIMEOUT, CANCELLED, EXPIRED
}

data class Substitutions(
        @JsonProperty("BRANCH_NAME") val branchName: String,
        @JsonProperty("COMMIT_SHA") val commitSha: String,
        @JsonProperty("REPO_NAME") val repoName: String
)

data class Build(val id: String, val projectId: String, val status: BuildStatus, val buildTriggerId: String, val startTime: String?, val finishTime: String?, val logUrl: String, val substitutions: Substitutions) {
    val branchName = substitutions.branchName
    val commitSha = substitutions.commitSha
    val repoName = substitutions.repoName
}

data class Attributes(val buildId: String, val status: BuildStatus)

data class Message(val attributes: Attributes, val data: Build)

data class Envelope(val message: Message)

@Singleton
class BuildMetrics {
    private val LOG = LoggerFactory.getLogger(BuildMetrics::class.java)

    fun send(build: Build) {
        if (build.status == BuildStatus.SUCCESS || build.status == BuildStatus.FAILURE) {
            val projectName = ProjectName.of(build.projectId)

            val service = "github.com/asdf/${build.repoName}"
            val version = build.buildTriggerId

            ErrorStatsServiceClient.create().use { errorStatsServiceClient ->
                val serviceContextFilter = ServiceContextFilter.newBuilder().setService(service).setVersion(version).build()
                val listGroupStatsRequest = ListGroupStatsRequest.newBuilder().setServiceFilter(serviceContextFilter).build()
                val groupStats = errorStatsServiceClient.listGroupStats(listGroupStatsRequest)
                println(groupStats)
            }

            ReportErrorsServiceClient.create().use { reportErrorsServiceClient ->
                val serviceContext = ServiceContext.newBuilder().setService(service).setVersion(version).build()

                val message = """
                    Build.Trigger.Failed: ${build.buildTriggerId}
                        at asdf
                        at zxcv
                """.trimIndent()

                val errorContext = ErrorContext.newBuilder().setReportLocation(SourceLocation.getDefaultInstance()).build()

                val customErrorEvent = ReportedErrorEvent.getDefaultInstance()
                        .toBuilder()
                        .setEventTime(Timestamps.fromMillis(System.currentTimeMillis())) //.parse(build.finishTime))
                        .setContext(errorContext)
                        .setServiceContext(serviceContext)
                        .setMessage(message)
                        .build()

                reportErrorsServiceClient.reportErrorEvent(projectName, customErrorEvent)

                ErrorGroupServiceClient.create().use { errorGroupServiceClient ->
                    errorGroupServiceClient.
                }
            }
        }
    }


}
