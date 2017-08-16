package yanagishima.service;

import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import me.geso.tinyorm.TinyORM;
import org.apache.commons.codec.digest.DigestUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.komamitsu.fluency.Fluency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yanagishima.config.YanagishimaConfig;
import yanagishima.exception.HiveQueryErrorException;
import yanagishima.result.HiveQueryResult;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static yanagishima.util.Constants.YANAGISHIAM_HIVE_JOB_PREFIX;
import static yanagishima.util.DbUtil.insertQueryHistory;
import static yanagishima.util.DbUtil.storeError;
import static yanagishima.util.PathUtil.getResultFilePath;
import static yanagishima.util.TimeoutUtil.checkTimeout;

public class HiveServiceImpl implements HiveService {

    private static Logger LOGGER = LoggerFactory.getLogger(HiveServiceImpl.class);

    private YanagishimaConfig yanagishimaConfig;

    private ExecutorService executorService = Executors.newFixedThreadPool(10);

    @Inject
    private TinyORM db;

    @Inject
    public HiveServiceImpl(YanagishimaConfig yanagishimaConfig) {
        this.yanagishimaConfig = yanagishimaConfig;
    }

    @Override
    public String doQueryAsync(String datasource, String query, String userName) {
        String queryId = generateQueryId(datasource, query);
        executorService.submit(new Task(queryId, datasource, query, userName));
        return queryId;
    }

    public class Task implements Runnable {
        private String queryId;

        private String datasource;
        private String query;
        private String userName;

        public Task(String queryId, String datasource, String query, String userName) {
            this.queryId = queryId;
            this.datasource = datasource;
            this.query = query;
            this.userName = userName;
        }

        @Override
        public void run() {
            try {
                int limit = yanagishimaConfig.getSelectLimit();
                getHiveQueryResult(this.queryId, this.datasource, this.query, true, limit, this.userName);
            } catch (Throwable e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
    }

    @Override
    public HiveQueryResult doQuery(String datasource, String query, String userName, boolean storeFlag, int limit) throws HiveQueryErrorException {
        String queryId = generateQueryId(datasource, query);
        return getHiveQueryResult(queryId, datasource, query, storeFlag, limit, userName);
    }

    private HiveQueryResult getHiveQueryResult(String queryId, String datasource, String query, boolean storeFlag, int limit, String userName) throws HiveQueryErrorException {
        try {
            Class.forName("org.apache.hive.jdbc.HiveDriver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        String url = yanagishimaConfig.getHiveJdbcUrl(datasource).get();
        String user = yanagishimaConfig.getHiveJdbcUser(datasource).get();
        String password = yanagishimaConfig.getHiveJdbcPassword(datasource).get();

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            long start = System.currentTimeMillis();
            HiveQueryResult hiveQueryResult = new HiveQueryResult();
            hiveQueryResult.setQueryId(queryId);
            processData(datasource, query, limit, connection, queryId, start, hiveQueryResult);
            if (storeFlag) {
                insertQueryHistory(db, datasource, "hive", query, queryId);
            }
            if (yanagishimaConfig.getFluentdExecutedTag().isPresent()) {
                String fluentdHost = yanagishimaConfig.getFluentdHost().orElse("localhost");
                int fluentdPort = Integer.parseInt(yanagishimaConfig.getFluentdPort().orElse("24224"));
                try (Fluency fluency = Fluency.defaultFluency(fluentdHost, fluentdPort)) {
                    long end = System.currentTimeMillis();
                    String tag = yanagishimaConfig.getFluentdExecutedTag().get();
                    Map<String, Object> event = new HashMap<>();
                    event.put("elapsed_time_millseconds", end - start);
                    event.put("user", userName);
                    event.put("query", query);
                    event.put("query_id", queryId);
                    event.put("datasource", datasource);
                    event.put("engine", "hive");
                    fluency.emit(tag, event);
                } catch (IOException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            return hiveQueryResult;

        } catch (SQLException e) {
            storeError(db, datasource, "hive", queryId, query, e.getMessage());
            throw new HiveQueryErrorException(queryId, e);
        }
    }

    private String generateQueryId(String datasource, String query) {
        String yyyyMMddHHmmss = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        double rand = Math.floor(Math.random() * 10000);
        return yyyyMMddHHmmss + "_" + DigestUtils.md5Hex(datasource + ";" + query + ";" + ZonedDateTime.now().toString() + ";" + String.valueOf(rand));
    }

    private void processData(String datasource, String query, int limit, Connection connection, String queryId, long start, HiveQueryResult hiveQueryResult) throws SQLException {
        Duration queryMaxRunTime = new Duration(this.yanagishimaConfig.getHiveQueryMaxRunTimeSeconds(datasource), TimeUnit.SECONDS);
        try(Statement statement = connection.createStatement()) {
            int timeout = (int) queryMaxRunTime.toMillis() / 1000;
            statement.setQueryTimeout(timeout);
            String jobName = YANAGISHIAM_HIVE_JOB_PREFIX + queryId;
            statement.execute("set mapreduce.job.name=" + jobName);
            try(ResultSet resultSet = statement.executeQuery(query)) {
                ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
                int columnCount = resultSetMetaData.getColumnCount();
                List<String> columnNameList = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    columnNameList.add(resultSetMetaData.getColumnName(i));
                }

                Path dst = getResultFilePath(datasource, queryId, false);
                int lineNumber = 0;
                int maxResultFileByteSize = yanagishimaConfig.getMaxResultFileByteSize();
                int resultBytes = 0;
                try (BufferedWriter bw = Files.newBufferedWriter(dst, StandardCharsets.UTF_8)) {
                    ObjectMapper columnsMapper = new ObjectMapper();
                    String columnsStr = columnsMapper.writeValueAsString(columnNameList) + "\n";
                    bw.write(columnsStr);
                    lineNumber++;
                    hiveQueryResult.setColumns(columnNameList);
                    List<List<String>> rowDataList = new ArrayList<>();
                    while (resultSet.next()) {
                        List<String> columnDataList = new ArrayList<>();
                        for (int i = 1; i <= columnCount; i++) {
                            Object resultObject = resultSet.getObject(i);
                            if (resultObject instanceof Long) {
                                columnDataList.add(((Long) resultObject).toString());
                            } else {
                                if (resultObject == null) {
                                    columnDataList.add(null);
                                } else {
                                    columnDataList.add(resultObject.toString());
                                }
                            }
                        }

                        try {
                            ObjectMapper resultMapper = new ObjectMapper();
                            String resultStr = resultMapper.writeValueAsString(columnDataList) + "\n";
                            bw.write(resultStr);
                            lineNumber++;
                            resultBytes += resultStr.getBytes(StandardCharsets.UTF_8).length;
                            if (resultBytes > maxResultFileByteSize) {
                                String message = String.format("Result file size exceeded %s bytes. queryId=%s", maxResultFileByteSize, queryId);
                                storeError(db, datasource, "hive", queryId, query, message);
                                throw new RuntimeException(message);
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        if (query.toLowerCase().startsWith("show") || rowDataList.size() < limit) {
                            rowDataList.add(columnDataList);
                        } else {
                            hiveQueryResult.setWarningMessage(String.format("now fetch size is %d. This is more than %d. So, fetch operation stopped.", rowDataList.size(), limit));
                        }

                        checkTimeout(db, queryMaxRunTime, start, datasource, "hive", queryId, query);
                    }
                    hiveQueryResult.setLineNumber(lineNumber);
                    hiveQueryResult.setRecords(rowDataList);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

                try {
                    long size = Files.size(dst);
                    DataSize rawDataSize = new DataSize(size, DataSize.Unit.BYTE);
                    hiveQueryResult.setRawDataSize(rawDataSize.convertToMostSuccinctDataSize());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

}
