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
import com.google.api.LabelDescriptor
import com.google.api.Metric
import com.google.api.MetricDescriptor
import com.google.api.MonitoredResource
import com.google.cloud.monitoring.v3.MetricServiceClient
import com.google.monitoring.v3.*
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

            try {
                val metricServiceClient = MetricServiceClient.create()

                val project = ProjectName.of(build.projectId)

                val metricType = "custom.googleapis.com/cloud-build-notifier-asdf"

                val descriptor = MetricDescriptor.newBuilder()
                        .setType(metricType)
                        .addLabels(
                                LabelDescriptor.newBuilder()
                                        .setKey("repo_name")
                                        .setValueType(LabelDescriptor.ValueType.STRING)
                        )
                        .setDescription("This is a simple example of a custom metric.")
                        .setMetricKind(MetricDescriptor.MetricKind.GAUGE)
                        .setValueType(MetricDescriptor.ValueType.BOOL)
                        .build()

                val createMetricDescriptorRequest = CreateMetricDescriptorRequest.newBuilder()
                        .setName(project.toString())
                        .setMetricDescriptor(descriptor)
                        .build()

                metricServiceClient.createMetricDescriptor(createMetricDescriptorRequest)


                val metricLabels = mapOf(
                        // todo: duration
                        "repo_name" to build.repoName,
                        "log_url" to build.logUrl,
                        "commit_sha" to build.commitSha,
                        "branchName" to build.branchName,
                        "build_trigger_id" to build.buildTriggerId,
                        "build_id" to build.id
                )

                val metric = Metric.newBuilder().setType(metricType).putAllLabels(metricLabels).build()

                val resourceLabels = mapOf(
                        "project_id" to build.projectId
                )

                val resource = MonitoredResource.newBuilder().setType("global").putAllLabels(resourceLabels).build()

                val interval = TimeInterval.newBuilder().setEndTime(Timestamps.parse(build.finishTime))
                val value = TypedValue.newBuilder().setBoolValue(build.status == BuildStatus.SUCCESS).build()
                val point = Point.newBuilder().setInterval(interval).setValue(value).build()

                val timeSeries = TimeSeries.newBuilder().setMetric(metric).setResource(resource).addPoints(point).build()

                val request = CreateTimeSeriesRequest.newBuilder()
                        .setName(project.toString())
                        .addTimeSeries(timeSeries)
                        .build()

                metricServiceClient.createTimeSeries(request)
            }
            catch (e: RuntimeException) {
                LOG.error("Error sending metric", e)
            }
        }
    }


}
