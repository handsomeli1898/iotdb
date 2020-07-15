/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.cluster.config;

import com.google.common.net.InetAddresses;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.iotdb.cluster.exception.BadSeedUrlFormatException;
import org.apache.iotdb.db.conf.IoTDBConstant;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterDescriptor {

  private static final Logger logger = LoggerFactory.getLogger(ClusterDescriptor.class);
  private static final ClusterDescriptor INSTANCE = new ClusterDescriptor();

  private static final String OPTION_META_PORT = "meta_port";
  private static final String OPTION_DATA_PORT = "data_port";
  private static final String OPTION_CLIENT_PORT = "client_port";
  private static final String OPTION_SEED_NODES = "seed_nodes";


  private ClusterConfig config = new ClusterConfig();
  private static CommandLine commandLine;

  private ClusterDescriptor() {
    loadProps();
  }

  public ClusterConfig getConfig() {
    return config;
  }

  public static ClusterDescriptor getInstance() {
    return INSTANCE;
  }

  public String getPropsUrl() {
    String url = System.getProperty(ClusterConstant.CLUSTER_CONF, null);
    if (url == null) {
      url = System.getProperty(IoTDBConstant.IOTDB_HOME, null);
      if (url != null) {
        url = url + File.separatorChar + "conf" + File.separatorChar + ClusterConfig.CONFIG_NAME;
      } else {
        logger.warn(
            "Cannot find IOTDB_HOME or CLUSTER_CONF environment variable when loading "
                + "config file {}, use default configuration",
            ClusterConfig.CONFIG_NAME);
        // update all data seriesPath
        return null;
      }
    } else {
      url += (File.separatorChar + ClusterConfig.CONFIG_NAME);
    }
    return url;
  }

  public void replaceProps(String[] params) {
    Options options = new Options();

    Option metaPort = new Option(OPTION_META_PORT, OPTION_META_PORT, true, "port for metadata service");
    metaPort.setRequired(false);
    options.addOption(metaPort);

    Option dataPort = new Option(OPTION_DATA_PORT, OPTION_DATA_PORT, true, "port for data service");
    metaPort.setRequired(false);
    options.addOption(dataPort);

    Option clientPort = new Option(OPTION_CLIENT_PORT, OPTION_CLIENT_PORT, true, "port for client service");
    metaPort.setRequired(false);
    options.addOption(clientPort);

    Option seedNodes = new Option(OPTION_SEED_NODES, OPTION_SEED_NODES, true,
        "comma-separated {IP/DOMAIN}:meta_port:data_port pairs");
    metaPort.setRequired(false);
    options.addOption(seedNodes);

    boolean ok = parseCommandLine(options, params);
    if (!ok) {
      logger.error("replaces properties failed, use default conf params");
    } else {
      if (commandLine.hasOption(OPTION_META_PORT)) {
        config.setLocalMetaPort(Integer.parseInt(commandLine.getOptionValue(OPTION_META_PORT)));
        logger.debug("replace local meta port with={}", config.getLocalMetaPort());
      }

      if (commandLine.hasOption(OPTION_DATA_PORT)) {
        config.setLocalDataPort(Integer.parseInt(commandLine.getOptionValue(OPTION_DATA_PORT)));
        logger.debug("replace local data port with={}", config.getLocalDataPort());
      }

      if (commandLine.hasOption(OPTION_CLIENT_PORT)) {
        config.setLocalClientPort(Integer.parseInt(commandLine.getOptionValue(OPTION_CLIENT_PORT)));
        logger.debug("replace local client port with={}", config.getLocalClientPort());
      }

      if (commandLine.hasOption(OPTION_SEED_NODES)) {
        String seedNodeUrls = commandLine.getOptionValue(OPTION_SEED_NODES);
        config.setSeedNodeUrls(getSeedUrlList(seedNodeUrls));
        logger.debug("replace seed nodes with={}", config.getSeedNodeUrls());
      }
    }
  }

  public void replaceHostnameWithIp() throws UnknownHostException, BadSeedUrlFormatException {
    boolean isInvalidLocalIp = InetAddresses.isInetAddress(config.getLocalIP());
    if (!isInvalidLocalIp) {
      String localIP = hostnameToIP(config.getLocalIP());
      config.setLocalIP(localIP);
    }

    List<String> newSeedUrls = new ArrayList<>();
    for (String seedUrl : config.getSeedNodeUrls()) {
      String[] splits = seedUrl.split(":");
      if (splits.length != 3) {
        throw new BadSeedUrlFormatException(seedUrl);
      }
      String seedIP = splits[0];
      boolean isInvalidSeedIp = InetAddresses.isInetAddress(seedIP);
      if (!isInvalidSeedIp) {
        String newSeedIP = hostnameToIP(seedIP);
        newSeedUrls.add(newSeedIP + ":" + splits[1] + ":" + splits[2]);
      } else {
        newSeedUrls.add(seedUrl);
      }
    }
    config.setSeedNodeUrls(newSeedUrls);
    logger.debug("after replace, the localIP={}, seedUrls={}", config.getLocalIP(),
        config.getSeedNodeUrls());
  }

  private static boolean parseCommandLine(Options options, String[] params) {
    try {
      CommandLineParser parser = new DefaultParser();
      commandLine = parser.parse(options, params);
    } catch (ParseException e) {
      logger.error("parse conf params failed", e);
      return false;
    }
    return true;
  }

  /**
   * load an property file and set TsfileDBConfig variables.
   */
  private void loadProps() {

    String url = getPropsUrl();
    Properties properties = System.getProperties();
    if (url != null) {
      try (InputStream inputStream = new FileInputStream(new File(url))) {
        logger.info("Start to read config file {}", url);
        properties.load(inputStream);
      } catch (IOException e) {
        logger.warn("Fail to find config file {}", url, e);
      }
    }
    config.setLocalIP(properties.getProperty("LOCAL_IP", config.getLocalIP()));

    config.setLocalMetaPort(Integer.parseInt(properties.getProperty("LOCAL_META_PORT",
        String.valueOf(config.getLocalMetaPort()))));

    config.setLocalDataPort(Integer.parseInt(properties.getProperty("LOCAL_DATA_PORT",
        Integer.toString(config.getLocalDataPort()))));

    config.setLocalClientPort(Integer.parseInt(properties.getProperty("LOCAL_CLIENT_PORT",
        Integer.toString(config.getLocalClientPort()))));

    config.setMaxConcurrentClientNum(Integer.parseInt(properties.getProperty(
        "MAX_CONCURRENT_CLIENT_NUM", String.valueOf(config.getMaxConcurrentClientNum()))));

    config.setReplicationNum(Integer.parseInt(properties.getProperty(
        "REPLICA_NUM", String.valueOf(config.getReplicationNum()))));

    config.setRpcThriftCompressionEnabled(Boolean.parseBoolean(properties.getProperty(
        "ENABLE_THRIFT_COMPRESSION", String.valueOf(config.isRpcThriftCompressionEnabled()))));

    config
        .setConnectionTimeoutInMS(Integer.parseInt(properties.getProperty("CONNECTION_TIME_OUT_MS",
            String.valueOf(config.getConnectionTimeoutInMS()))));

    config
        .setQueryTimeoutInSec(Integer.parseInt(properties.getProperty("QUERY_TIME_OUT_SEC",
            String.valueOf(config.getQueryTimeoutInSec()))));

    config
        .setMaxRemovedLogSize(Long.parseLong(properties.getProperty("MAX_REMOVED_LOG_SIZE",
            String.valueOf(config.getMaxRemovedLogSize()))));

    config.setUseBatchInLogCatchUp(Boolean.parseBoolean(properties.getProperty(
        "USE_BATCH_IN_CATCH_UP", String.valueOf(config.isUseBatchInLogCatchUp()))));

    config.setMaxNumberOfLogs(Integer.parseInt(
        properties.getProperty("MAX_NUMBER_OF_LOGS", String.valueOf(config.getMaxNumberOfLogs()))));

    config.setLogDeleteCheckIntervalSecond(Integer.parseInt(properties
        .getProperty("LOG_DELETION_CHECK_INTERVAL_SECOND",
            String.valueOf(config.getLogDeleteCheckIntervalSecond()))));

    config.setEnableAutoCreateSchema(Boolean.parseBoolean(properties
        .getProperty("ENABLE_AUTO_CREATE_SCHEMA",
            String.valueOf(config.isEnableAutoCreateSchema()))));

    String consistencyLevel = properties.getProperty("CONSISTENCY_LEVEL");
    if (consistencyLevel != null) {
      config.setConsistencyLevel(ConsistencyLevel.getConsistencyLevel(consistencyLevel));
    }

    String seedUrls = properties.getProperty("SEED_NODES");
    if (seedUrls != null) {
      List<String> urlList = getSeedUrlList(seedUrls);
      config.setSeedNodeUrls(urlList);
    }
  }

  private List<String> getSeedUrlList(String seedUrls) {
    if (seedUrls == null) {
      return Collections.emptyList();
    }
    List<String> urlList = new ArrayList<>();
    String[] split = seedUrls.split(",");
    for (String nodeUrl : split) {
      nodeUrl = nodeUrl.trim();
      if ("".equals(nodeUrl)) {
        continue;
      }
      urlList.add(nodeUrl);
    }
    return urlList;
  }

  public void loadHotModifiedProps() throws QueryProcessException {
    Properties properties = getProperties();
    if (properties != null) {
      loadHotModifiedProps(properties);
    }
  }

  private Properties getProperties() throws QueryProcessException {
    String url = getPropsUrl();
    if (url == null) {
      return null;
    }
    Properties properties;
    try (InputStream inputStream = new FileInputStream(new File(url))) {
      logger.info("Start to reload config file {}", url);
      properties = new Properties();
      properties.load(inputStream);
    } catch (Exception e) {
      throw new QueryProcessException(
          String.format("Fail to reload config file %s because %s", url, e.getMessage()));
    }
    return properties;
  }

  /**
   * This method is for setting hot modified properties of the cluster. Currently, we support
   * max_concurrent_client_num, connection_time_out_ms, max_resolved_log_size
   *
   * @param properties
   * @throws QueryProcessException
   */
  public void loadHotModifiedProps(Properties properties) {

    config.setMaxConcurrentClientNum(Integer.parseInt(properties
        .getProperty("MAX_CONCURRENT_CLIENT_NUM",
            String.valueOf(config.getMaxConcurrentClientNum()))));

    config.setConnectionTimeoutInMS(Integer.parseInt(properties
        .getProperty("CONNECTION_TIME_OUT_MS", String.valueOf(config.getConnectionTimeoutInMS()))));

    config.setMaxRemovedLogSize(Long.parseLong(properties
        .getProperty("MAX_REMOVED_LOG_SIZE", String.valueOf(config.getMaxRemovedLogSize()))));

    logger.info("Set cluster configuration {}", properties);
  }

  public String hostnameToIP(String hostname) throws UnknownHostException {
    InetAddress address = InetAddress.getByName(hostname);
    return address.getHostAddress();
  }

}