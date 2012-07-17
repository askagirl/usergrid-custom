/*******************************************************************************
 * Copyright 2012 Apigee Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.usergrid.rest;

import static org.usergrid.persistence.cassandra.CassandraService.MANAGEMENT_APPLICATION_ID;

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.UriInfo;

import com.google.common.collect.BiMap;
import com.sun.jersey.api.NotFoundException;
import com.sun.jersey.api.spring.Autowire;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.annotation.ExceptionMetered;
import com.yammer.metrics.annotation.Timed;
import com.yammer.metrics.core.*;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.reporting.CsvReporter;
import com.yammer.metrics.stats.Snapshot;
import org.apache.commons.lang.StringUtils;
import org.apache.shiro.authz.UnauthorizedException;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.usergrid.rest.applications.ApplicationResource;
import org.usergrid.rest.exceptions.NoOpException;

import com.sun.jersey.api.json.JSONWithPadding;
import org.usergrid.rest.exceptions.OrganizationApplicationNotFoundException;
import org.usergrid.rest.security.annotations.RequireSystemAccess;
import org.usergrid.rest.utils.PathingUtils;
import org.usergrid.system.UsergridSystemMonitor;

/**
 * 
 * @author ed@anuff.com
 */
@Path("/")
@Component
@Scope("singleton")
@Produces({ MediaType.APPLICATION_JSON, "application/javascript",
		"application/x-javascript", "text/ecmascript",
		"application/ecmascript", "text/jscript" })
public class RootResource extends AbstractContextResource implements MetricProcessor<RootResource.MetricContext> {

  static final class MetricContext {
    final boolean showFullSamples;
    final ObjectNode objectNode;

    MetricContext(ObjectNode objectNode, boolean showFullSamples) {
        this.objectNode = objectNode;
        this.showFullSamples = showFullSamples;
    }
  }

	private static final Logger logger = LoggerFactory
			.getLogger(RootResource.class);

	long started = System.currentTimeMillis();

  @Autowired
  private UsergridSystemMonitor usergridSystemMonitor;

	public RootResource() {
	}

  @RequireSystemAccess
	@GET
	@Path("applications")
	public JSONWithPadding getAllApplications(@Context UriInfo ui,
			@QueryParam("callback") @DefaultValue("callback") String callback)
			throws URISyntaxException {

		logger.info("RootResource.getAllApplications");

		ApiResponse response = new ApiResponse(ui);
		response.setAction("get applications");

		Map<String, UUID> applications = null;
		try {
			applications = emf.getApplications();
			response.setSuccess();
			response.setApplications(applications);
		} catch (Exception e) {
			logger.info("Unable to retrieve applications", e);
			response.setError("Unable to retrieve applications");
		}

		return new JSONWithPadding(response, callback);
	}

  @RequireSystemAccess
	@GET
	@Path("apps")
	public JSONWithPadding getAllApplications2(@Context UriInfo ui,
			@QueryParam("callback") @DefaultValue("callback") String callback)
			throws URISyntaxException {
		return getAllApplications(ui, callback);
	}

	@GET
	public Response getRoot(@Context UriInfo ui) throws URISyntaxException {

		String redirect_root = properties.getProperty("usergrid.redirect_root");
		if (StringUtils.isNotBlank(redirect_root)) {
			ResponseBuilder response = Response.temporaryRedirect(new URI(
					redirect_root));
			return response.build();
		} else {
			ResponseBuilder response = Response.temporaryRedirect(new URI(
					"/status"));
			return response.build();
		}
	}

	@GET
	@Path("status")
	public JSONWithPadding getStatus(
			@QueryParam("callback") @DefaultValue("callback") String callback) {
		ApiResponse response = new ApiResponse();

		ObjectNode node = JsonNodeFactory.instance.objectNode();
		node.put("started", started);
		node.put("uptime", System.currentTimeMillis() - started);
    node.put("version", usergridSystemMonitor.getBuildNumber());
    node.put("cassandraAvailable", usergridSystemMonitor.getIsCassandraAlive());
    dumpMetrics(node);
		response.setProperty("status", node);
		return new JSONWithPadding(response, callback);
	}

  private void dumpMetrics(ObjectNode node) {
    MetricsRegistry registry = Metrics.defaultRegistry();

    for (Map.Entry<String, SortedMap<MetricName, Metric>> entry : registry.groupedMetrics().entrySet()) {

      ObjectNode meterNode = JsonNodeFactory.instance.objectNode();

        for (Map.Entry<MetricName, Metric> subEntry : entry.getValue().entrySet()) {
          ObjectNode metricNode = JsonNodeFactory.instance.objectNode();

          try {
            subEntry.getValue()
                    .processWith(this,
                            subEntry.getKey(),
                            new MetricContext(metricNode, true));
          } catch (Exception e) {
            logger.warn("Error writing out {}", subEntry.getKey(), e);
          }
          meterNode.put(subEntry.getKey().getName(), metricNode);

        }
      node.put(entry.getKey(),meterNode);
    }
  }


	@Path("{applicationId: [A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}}")
	public ApplicationResource getApplicationById(
			@PathParam("applicationId") String applicationIdStr)
			throws Exception {

		if ("options".equalsIgnoreCase(request.getMethod())) {
			throw new NoOpException();
		}

		UUID applicationId = UUID.fromString(applicationIdStr);
		if (applicationId == null) {
			return null;
		}

    return appResourceFor(applicationId);
	}

  private ApplicationResource appResourceFor(UUID applicationId) throws Exception {
    if (applicationId.equals(MANAGEMENT_APPLICATION_ID)) {
      throw new UnauthorizedException();
    }

    return getSubResource(ApplicationResource.class).init(applicationId);
  }

  @Path("applications/{applicationId: [A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}}")
	public ApplicationResource getApplicationById2(
			@PathParam("applicationId") String applicationId) throws Exception {
		return getApplicationById(applicationId);
	}

	@Path("apps/{applicationId: [A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}}")
	public ApplicationResource getApplicationById3(
			@PathParam("applicationId") String applicationId) throws Exception {
		return getApplicationById(applicationId);
	}

  @Timed(name = "getApplicationByUuids_timer",group = "rest_timers")
  @ExceptionMetered(group = "rest_exceptions", name = "getApplicationByUuids_exceptions")
  @Path("{organizationId: [A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}}/{applicationId: [A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}}")
  public ApplicationResource getApplicationByUuids(@PathParam("organizationId") String organizationIdStr,
                                                   @PathParam("applicationId") String applicationIdStr)

    throws Exception {

    UUID applicationId = UUID.fromString(applicationIdStr);
    UUID organizationId = UUID.fromString(organizationIdStr);
    if (applicationId == null || organizationId == null ) {
      return null;
    }
    BiMap<UUID,String> apps = management.getApplicationsForOrganization(organizationId);
    if ( apps.get(applicationId) == null ) {
      return null;
    }
    return appResourceFor(applicationId);
  }

  @Timed(name = "getApplicationByName_timer",group = "rest_timers")
  @ExceptionMetered(group = "rest_exceptions", name = "getApplicationByName_exceptions")
	@Path("{organizationName}/{applicationName}")
	public ApplicationResource getApplicationByName(
          @PathParam("organizationName") String organizationName,
          @PathParam("applicationName") String applicationName)
			throws Exception {

		if ("options".equalsIgnoreCase(request.getMethod())) {
			throw new NoOpException();
		}

    String orgAppName = PathingUtils.assembleAppName(organizationName, applicationName);
    UUID applicationId = emf.lookupApplication(orgAppName);
		if (applicationId == null) {
      throw new OrganizationApplicationNotFoundException(orgAppName, uriInfo);
		}

		return appResourceFor(applicationId);
	}

	@Path("applications/{organizationName}/{applicationName}")
	public ApplicationResource getApplicationByName2(
          @PathParam("organizationName") String organizationName,
          @PathParam("applicationName") String applicationName)
			throws Exception {
		return getApplicationByName(organizationName, applicationName);
	}

	@Path("apps/{organizationName}/{applicationName}")
	public ApplicationResource getApplicationByName3(
      @PathParam("organizationName") String organizationName,
			@PathParam("applicationName") String applicationName)
			throws Exception {
		return getApplicationByName(organizationName, applicationName);
	}

  @Override
  public void processHistogram(MetricName name, Histogram histogram, MetricContext context) throws Exception {
    final ObjectNode node = context.objectNode;
    node.put("type", "histogram");
    node.put("count", histogram.count());
    writeSummarizable(histogram, node);
    writeSampling(histogram, node);
  }

  @Override
  public void processCounter(MetricName name, Counter counter, MetricContext context) throws Exception {
    final ObjectNode node = context.objectNode;
    node.put("type", "counter");
    node.put("count", counter.count());
  }

  @Override
  public void processGauge(MetricName name, Gauge<?> gauge, MetricContext context) throws Exception {
    final ObjectNode node = context.objectNode;
    node.put("type", "gauge");
    node.put("vale","[disabled]");
  }

  @Override
  public void processMeter(MetricName name, Metered meter, MetricContext context) throws Exception {
    final ObjectNode node = context.objectNode;
    node.put("type", "meter");
    node.put("event_type", meter.eventType());
    writeMeteredFields(meter, node);

  }

  @Override
  public void processTimer(MetricName name, Timer timer, MetricContext context) throws Exception {
    final ObjectNode node = context.objectNode;

    node.put("type", "timer");
    //json.writeFieldName("duration");
    node.put("unit", timer.durationUnit().toString().toLowerCase());
    ObjectNode durationNode = JsonNodeFactory.instance.objectNode();
    writeSummarizable(timer, durationNode);
    writeSampling(timer, durationNode);
    node.put("duration",durationNode);
    writeMeteredFields(timer, node);

  }

  private static Object evaluateGauge(Gauge<?> gauge) {
    try {
      return gauge.value();
    } catch (RuntimeException e) {
      logger.warn("Error evaluating gauge", e);
      return "error reading gauge: " + e.getMessage();
    }
  }

  private static void writeSummarizable(Summarizable metric, ObjectNode mNode) throws IOException {
    mNode.put("min", metric.min());
    mNode.put("max", metric.max());
    mNode.put("mean", metric.mean());
    mNode.put("std_dev", metric.stdDev());
  }

  private static void writeSampling(Sampling metric, ObjectNode mNode) throws IOException {

    final Snapshot snapshot = metric.getSnapshot();
    mNode.put("median", snapshot.getMedian());
    mNode.put("p75", snapshot.get75thPercentile());
    mNode.put("p95", snapshot.get95thPercentile());
    mNode.put("p98", snapshot.get98thPercentile());
    mNode.put("p99", snapshot.get99thPercentile());
    mNode.put("p999", snapshot.get999thPercentile());
  }

  private static void writeMeteredFields(Metered metered, ObjectNode node) throws IOException {
    ObjectNode mNode = JsonNodeFactory.instance.objectNode();
    mNode.put("unit", metered.rateUnit().toString().toLowerCase());
    mNode.put("count", metered.count());
    mNode.put("mean", metered.meanRate());
    mNode.put("m1", metered.oneMinuteRate());
    mNode.put("m5", metered.fiveMinuteRate());
    mNode.put("m15", metered.fifteenMinuteRate());
    node.put("rate",mNode);
  }

}
