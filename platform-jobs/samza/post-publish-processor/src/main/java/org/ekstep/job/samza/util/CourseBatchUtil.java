package org.ekstep.job.samza.util;

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.MessageCollector;
import org.ekstep.cassandra.connector.util.CassandraConnector;
import org.ekstep.common.Platform;
import org.ekstep.common.dto.Response;
import org.ekstep.common.enums.TaxonomyErrorCodes;
import org.ekstep.common.exception.ResponseCode;
import org.ekstep.common.exception.ServerException;

import org.ekstep.jobs.samza.util.JobLogger;

import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class CourseBatchUtil {

    private static ObjectMapper mapper = new ObjectMapper();
    private static final String keyspace = Platform.config.hasPath("courses.keyspace.name")
            ? Platform.config.getString("courses.keyspace.name"): "sunbird_courses";

    private static final String KAFKA_TOPIC = Platform.config.hasPath("courses.topic")
            ? Platform.config.getString("courses.topic"): "local.coursebatch.job.request";

    private static final String CREATE_BATCH_URL = Platform.config.getString("lms_service.base_url") + "/private/v1/course/batch/create";

    private static JobLogger LOGGER = new JobLogger(CourseBatchUtil.class);


    public void syncCourseBatch(String courseId, MessageCollector collector) {
        //Get Coursebatch from course_batch table using courseId
        List<Row> courseBatchRows = readbatch("course_batch", courseId);

        //For each batch exists. fetch enrollment from user_courses table and push the message to kafka
        for(Row row: courseBatchRows) {
            if(1 == row.getInt("status")) {
                List<Row> userCoursesRows = read("user_courses", Arrays.asList(row.getString("batchid")));
                pushEventsToKafka(userCoursesRows, collector);
                LOGGER.info("Pushed the events to sync courseBatch enrollment for : " + courseId);
            }
        }
    }

    public void create(String courseId, String name, Double pkgVersion) {
       if(pkgVersion == 1.0 || pkgVersion == 1) {
           createBatch(courseId, name);
       } else {
           List<Row> openBatchRows = getOpenBatch("course_batch", courseId);
           if(CollectionUtils.isNotEmpty(openBatchRows) && openBatchRows.size()>=1)
               LOGGER.info(openBatchRows.size() +" Open Batch Found for : " + courseId+" | So skipping the create batch event.");
           else
               createBatch(courseId, name);
       }
    }


    private void pushEventsToKafka(List<Row> rows, MessageCollector collector) {
        for(Row row: rows) {
            try {
                Map<String, Object> rowMap = mapper.readValue(row.getString("[json]"), Map.class);
                Map<String, Object> event = generatekafkaEvent(rowMap);
                collector.send(new OutgoingMessageEnvelope(new SystemStream("kafka", KAFKA_TOPIC), event));
            } catch (Exception e) {
                LOGGER.error("Error while pushing the event for course batch enrollment sync", e);
            }

        }
    }


    private static List<Row> read(String table, List<String> batchIds) {
        Session session = CassandraConnector.getSession("sunbird");
        Select.Where selectQuery = null;
        if(null != batchIds && !batchIds.isEmpty() && StringUtils.equalsIgnoreCase("user_courses", table)){
            selectQuery = QueryBuilder.select().json().all().from(keyspace, table).where(QueryBuilder.in("batchid", batchIds));
        } else{
            selectQuery = QueryBuilder.select().json().all().from(keyspace, table).where();
        }
        ResultSet results = session.execute(selectQuery);
        return results.all();
    }

    private static List<Row> readbatch(String table, String courseId) {
        Session session = CassandraConnector.getSession("sunbird");
        Select.Where selectQuery = null;
        if(StringUtils.isNotBlank(courseId)){
            selectQuery = QueryBuilder.select().all().from(keyspace, table).where(QueryBuilder.eq("courseid", courseId));
        } else{
            selectQuery = QueryBuilder.select().all().from(keyspace, table).where();
        }
        ResultSet results = session.execute(selectQuery);
        return results.all();
    }

    private Map<String, Object> generatekafkaEvent(Map<String, Object> rowMap) throws JsonProcessingException {
        return new HashMap<String, Object>() {{
            put("eid", "BE_JOB_REQUEST");
            put("ets", System.currentTimeMillis());
            put("mid", "LP." + System.currentTimeMillis() +"." + UUID.randomUUID());
            put("actor", new HashMap<String, Object>(){{
                put("type", "System");
                put("id", "Course Batch Updater");
            }});
            put("context", new HashMap<String, Object>(){{
                put("pdata", new HashMap<String, Object>(){{
                    put("id", "org.sunbird.platform");
                    put("ver", "1.0");
                }});
            }});
            put("object", new HashMap<String, Object>(){{
                put("type", "CourseBatchEnrolment");
                put("id", rowMap.get("batchid") + "_" + rowMap.get("userid"));
            }});
            put("edata", new HashMap<String, Object>(){{
                put("action", "batch-enrolment-sync");
                put("iteration", 1);
                put("batchId", rowMap.get("batchid"));
                put("userId", rowMap.get("userid"));
                put("courseId", rowMap.get("courseid"));
                put("reset", Arrays.asList("completionPercentage","status","progress"));
            }});
        }};
    }

    private static void createBatch(String courseId, String name) {
        try {
            Map<String, Object> request = new HashMap<String, Object>() {{
                put(PostPublishParams.request.name(), new HashMap<String, Object>() {{
                    put(PostPublishParams.courseId.name(), courseId);
                    put(PostPublishParams.name.name(), name);
                    put(PostPublishParams.enrollmentType.name(), "open");
                    put(PostPublishParams.startDate.name(), new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
                }});
            }};

            Map<String, String> headerParam = new HashMap<String, String>() {{
                put("Content-Type", "application/json");
            }};
            HttpResponse<String> httpResponse = Unirest.post(CREATE_BATCH_URL)
                    .headers(headerParam)
                    .body(mapper.writeValueAsString(request)).asString();
            Response response = getResponse(httpResponse);
            if (response.getResponseCode() == ResponseCode.OK) {
                LOGGER.info("Result Received While Creating Batch for " + courseId +" | Result is : "+response.getResult());
                if (MapUtils.isNotEmpty(response.getResult()) && StringUtils.isNotBlank((String) response.getResult().get("batchId"))) {
                    LOGGER.info("Open Batch Successfully Created For "+courseId + " | Batch Id : "+response.getResult().get("batchId") + " , Batch Name : "+name);
                }
                else
                    LOGGER.info("Empty Result Received While Creating Batch for " + courseId);
            } else {
                LOGGER.info("Error Response Received While Creating Batch For " + courseId+ " | Error Response Code is :" + response.getResponseCode() + "| Error Result : " + response.getResult());
            }
        } catch (Exception e) {
            LOGGER.error("Exception Occurred While Creating Batch For " + courseId + " | Exception is :" , e);
            e.printStackTrace();
        }
    }

    private static Response getResponse(HttpResponse<String> response) {
        String body = null;
        Response resp = new Response();
        try {
            body = response.getBody();
            if (StringUtils.isNotBlank(body))
                resp = mapper.readValue(body, Response.class);
        } catch (UnsupportedEncodingException e) {
            LOGGER.error("UnsupportedEncodingException:::::" , e);
            throw new ServerException(TaxonomyErrorCodes.SYSTEM_ERROR.name(), e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Exception:::::" , e);
            throw new ServerException(TaxonomyErrorCodes.SYSTEM_ERROR.name(), e.getMessage());
        }
        return resp;
    }

    private static List<Row> getOpenBatch(String table, String courseId) {
        Session session = CassandraConnector.getSession("sunbird");
        Select.Where selectQuery = QueryBuilder.select().all().from(keyspace, table).where(QueryBuilder.eq("courseid", courseId));
        ResultSet results = session.execute(selectQuery);
        List<Row> courseBatchRows = results.all();
        List<Row> openBatchRows = courseBatchRows.stream().filter(row -> (StringUtils.equalsIgnoreCase("Open", row.getString("enrollmenttype")) && (0 == row.getInt("status") || 1 == row.getInt("status")))).collect(Collectors.toList());
        return openBatchRows;
    }
}
