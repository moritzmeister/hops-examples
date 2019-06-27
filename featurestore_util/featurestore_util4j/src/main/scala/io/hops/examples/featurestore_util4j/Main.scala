package io.hops.examples.featurestore_util4j

import org.apache.log4j.{Level, LogManager, Logger}
import org.apache.spark.sql.SparkSession
import org.apache.spark.SparkConf
import io.hops.util.Hops
import org.rogach.scallop.ScallopConf

import scala.collection.JavaConversions._
import scala.collection.JavaConversions
import scala.language.implicitConversions

/**
  * Parser of command-line arguments
  */
class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
  val features = opt[String](required = false, descr = "comma separated list of features", default = Some(""))
  val featuregroups = opt[String](required = false, descr = "comma separated list of featuregroups on the form " +
    "`featuregroup:version` where the features reside", default = Some(""))
  val featurestore = opt[String](required = false, descr = "name of the featurestore to apply the operation to",
    default = Some(""))
  val trainingdataset = opt[String](required = false, descr = "name of the training dataset", default = Some(""))
  val featuregroup = opt[String](required = false, descr = "name of the feature group", default = Some(""))
  val joinkey = opt[String](required = false, descr = "join key for joining the features together", default = Some(null))
  val description = opt[String](required = false, descr = "description", default = Some(""))
  val dataformat = opt[String](required = false, descr = "data format for training dataset", default = Some("parquet"))
  val version = opt[String](required = false, descr = "version", default = Some("1"))
  val descriptivestats = opt[Boolean](descr = "flag whether to compute descriptive stats")
  val featurecorrelation = opt[Boolean](descr = "flag whether to compute feature correlations")
  val clusteranalysis = opt[Boolean](descr = "flag whether to compute cluster analysis")
  val featurehistograms = opt[Boolean](descr = "flag whether to compute feature histograms")
  val statColumns = opt[String](required = false,
    descr = "comma separated list of columns to apply statisics to (if empty use all columns)",
    default = Some(""))
  val operation = opt[String](required = true, descr = "the featurestore operation")
  val sqlquery = opt[List[String]](required = false, descr = "custom SQL query to run against a Hive Database or JDBC" +
    " " +
    "backend")
  val hivedb = opt[String](required = false, descr = "Hive Database to Apply SQL query to ")
  verify()
}

/**
  * Program entry point
  *
  * This Scala program contains utility functions for starting
  * Apache Spark jobs for doing common operations in The Hopsworks Feature Store.
  * Such as, (1) creating a training dataset from a set of features. It will take the set of features and a join key as
  * input arguments, join the features together into a spark dataframe,
  * and write it out as a training dataset; (2) updating feature group or training dataset statistics.
  */
object Main {

  def main(args: Array[String]): Unit = {

    // Setup logging
    val log = LogManager.getLogger(Main.getClass.getName)
    log.setLevel(Level.INFO)
    log.info(s"Starting Sample Feature Engineering Job For Feature Store Examples")

    //Parse cmd arguments
    val conf = new Conf(args)
    val operation = conf.operation()

    // Setup Spark
    var sparkConf: SparkConf = null
    sparkConf = sparkClusterSetup()
    val spark = SparkSession.builder().config(sparkConf).enableHiveSupport().getOrCreate()
    val sc = spark.sparkContext

    //Perform actions
    operation match {
      case "create_td" => createTrainingDataset(conf, log)
      case "update_fg_stats" => updateFeaturegroupStats(conf, log)
      case "update_td_stats" => updateTrainingDatasetStats(conf, log)
      case "spark_sql_create_fg" => createFeaturegroupFromSparkSql(conf, log)
      case "jdbc_sql_create_fg" => createFeaturegroupFromJdbcSql(conf, log)
    }

    //Cleanup
    log.info("Shutting down spark job")
    spark.close
  }

  /**
    * Pre-process comma-separated features list
    *
    * @param featuresStr the string to process
    * @return a list of features
    */
  def preProcessFeatures(featuresStr: String): List[String] = {
    if (featuresStr.isEmpty)
      throw new IllegalArgumentException("Features cannot be empty")
    featuresStr.split(",").toList
  }

  /**
    * Pre-process comma-separated list of stat columns
    *
    * @param statColumnsStr the string to process
    * @return list of stat columns
    */
  def preProcessStatColumns(statColumnsStr: String): List[String] = {
    if(statColumnsStr.equals("")){
      List[String]()
    } else {
      statColumnsStr.split(",").toList
    }
  }

  /**
    * Pre-process list of SQL query
    *
    * @param sqlQueryList the list of space separated input words from command-line
    * @return a joined SQL string
    */
  def preProcessSqlQuery(sqlQueryList: List[String]): String = {
    if(sqlQueryList.isEmpty){
      throw new IllegalArgumentException("SQL Query Cannot Empty")
    } else {
      return sqlQueryList.mkString(" ")
    }
  }

  /**
    * Pre-process comma-separated list of stat columns
    *
    * @param statColumnsStr the string to process
    * @return list of stat columns
    */
  def preProcessFeaturestore(featurestoreStr: String): String = {
    if(featurestoreStr.equals("")){
      return Hops.getProjectFeaturestore.read
    } else {
      featurestoreStr
    }
  }

  /**
    * Pre-process comma-separated list of featuregroup:version
    *
    * @param statColumnsStr the string to process
    * @return map of featuregroup --> version
    */
  def preProcessFeatureGroups(featuregroupsVersionsStr: String): java.util.Map[String, Integer]
  = {
    if (featuregroupsVersionsStr.isEmpty)
      throw new IllegalArgumentException("Feature Groups cannot be empty")
    val featuregroupsVersions = featuregroupsVersionsStr.split(",")
    val scalaFeaturegroupsMap = featuregroupsVersions.map((fgVersion: String) => {
      val fgVersionArr = fgVersion.split(":")
      val fg = fgVersionArr(0)
      val version = new Integer(fgVersionArr(1).toInt)
      (fg, version)
    }).toMap
    return JavaConversions.mapAsJavaMap(scalaFeaturegroupsMap)
  }

  /**
    * Creates a Feature Group in the featurestore based on the result of a SparkSQL query (as specified in the
    * command-line arguments).
    *
    * @param conf the command-line arguments
    * @param log logger
    */
  def createFeaturegroupFromSparkSql(conf: Conf, log: Logger): Unit = {
    //Parse arguments
    val sqlQuery = preProcessSqlQuery(conf.sqlquery())
    val hiveDb = conf.hivedb()
    val featuregroup = conf.featuregroup()
    val description = conf.description()
    val version = conf.version().toInt
    val descriptiveStats = conf.descriptivestats()
    val featureCorrelation = conf.featurecorrelation()
    val clusterAnalysis = conf.clusteranalysis()
    val featureHistograms = conf.featurehistograms()
    val statColumns = preProcessStatColumns(conf.statColumns())
    val featurestoreToQuery = preProcessFeaturestore(conf.featurestore())

    //Run SparkSQL Command
    log.info(s"Running SQL Command: ${sqlQuery} against database: ${hiveDb}")
    val spark = Hops.findSpark()
    spark.sql("use " + hiveDb)
    val resultDf = spark.sql(sqlQuery)

    //Create Feature Group of the Results
    log.info(s"Creating Feature Group ${featuregroup}")
    Hops.createFeaturegroup(featuregroup)
      .setDataframe(resultDf)
      .setFeaturestore(featurestoreToQuery)
      .setDescriptiveStats(descriptiveStats)
      .setFeatureCorr(featureCorrelation)
      .setFeatureHistograms(featureHistograms)
      .setClusterAnalysis(clusterAnalysis)
      .setStatColumns(statColumns)
      .setDescription(description)
      .setVersion(version).write()
  }


  /**
    * Creates a Feature Group in the featurestore based on the result of a JDBC Sql query (as specified in the
    * command-line arguments).
    *
    * @param conf the command-line arguments
    * @param log logger
    */
  def createFeaturegroupFromJdbcSql(conf: Conf, log: Logger): Unit = {
    //jdbc_url = "jdbc:hive2://10.0.2.15:9085/demo_featurestore_admin000;auth=noSasl;ssl=true;twoWay=true;"
    
  }

  /**
    * Creates a training dataset in the featurestore based on command-line arguments
    *
    * @param conf the command-line arguments
    * @param log logger
    */
  def createTrainingDataset(conf: Conf, log: Logger): Unit = {

    //Parse arguments
    val features = preProcessFeatures(conf.features())
    val featuregroupsVersionMap = preProcessFeatureGroups(conf.featuregroups())
    val joinKey = conf.joinkey()
    val featurestoreToQuery = preProcessFeaturestore(conf.featurestore())
    val trainingDatasetName = conf.trainingdataset()
    val trainingDatasetDesc = conf.description()
    val trainingDatasetDataFormat = conf.dataformat()
    val trainingDatasetVersion = conf.version().toInt
    val descriptiveStats = conf.descriptivestats()
    val featureCorrelation = conf.featurecorrelation()
    val clusterAnalysis = conf.clusteranalysis()
    val featureHistograms = conf.featurehistograms()
    val statColumns = preProcessStatColumns(conf.statColumns())

    log.info(s"Fetching features: ${conf.features()} from the feature store")

    //Get Features
    val featuresDf = Hops.getFeatures(features)
      .setFeaturestore(featurestoreToQuery)
      .setFeaturegroupsAndVersions(featuregroupsVersionMap)
      .read()

    log.info(s"Saving the joined features to a training dataset: ${conf.trainingdataset()}")

    // Save as Training Dataset
    Hops.createTrainingDataset(trainingDatasetName)
      .setDataframe(featuresDf)
      .setFeaturestore(featurestoreToQuery)
      .setVersion(trainingDatasetVersion)
      .setDescription(trainingDatasetDesc)
      .setDataFormat(trainingDatasetDataFormat)
      .setDescriptiveStats(descriptiveStats)
      .setFeatureCorr(featureCorrelation)
      .setFeatureHistograms(featureHistograms)
      .setClusterAnalysis(clusterAnalysis)
      .setStatColumns(statColumns)
      .write()

    log.info(s"Training Dataset Saved Successfully")
  }

  /**
    * Updates featuregroup statistics based on command-line arguments
    *
    * @param conf command-line arguments
    * @param log logger
    */
  def updateFeaturegroupStats(conf: Conf, log: Logger): Unit = {
    val featuregroup = conf.featuregroup()
    val version = conf.version().toInt
    val descriptiveStats = conf.descriptivestats()
    val featureCorrelation = conf.featurecorrelation()
    val clusterAnalysis = conf.clusteranalysis()
    val featureHistograms = conf.featurehistograms()
    val featurestoreToQuery = preProcessFeaturestore(conf.featurestore())
    val statColumns = preProcessStatColumns(conf.statColumns())

    log.info(s"Updating Feature Group Statistics for Feature Group: ${featuregroup}")

    Hops.updateFeaturegroupStats(featuregroup)
      .setFeaturestore(featurestoreToQuery)
      .setVersion(version)
      .setDescriptiveStats(descriptiveStats)
      .setFeatureCorr(featureCorrelation)
      .setFeatureHistograms(featureHistograms)
      .setClusterAnalysis(clusterAnalysis)
      .setStatColumns(statColumns)
      .write()

    log.info(s"Statistics updated successfully")
  }

  /**
    * Updates training dataset statistics based on command-line arguments
    *
    * @param conf command-line arguments
    * @param log logger
    */
  def updateTrainingDatasetStats(conf: Conf, log: Logger): Unit = {
    val trainingDataset = conf.trainingdataset()
    val version = conf.version().toInt
    val descriptiveStats = conf.descriptivestats()
    val featureCorrelation = conf.featurecorrelation()
    val clusterAnalysis = conf.clusteranalysis()
    val featureHistograms = conf.featurehistograms()
    val featurestoreToQuery = preProcessFeaturestore(conf.featurestore())
    val statColumns = preProcessStatColumns(conf.statColumns())

    log.info(s"Update Training Dataset Stats")

    Hops.updateTrainingDatasetStats(trainingDataset)
      .setFeaturestore(featurestoreToQuery)
      .setVersion(version)
      .setDescriptiveStats(descriptiveStats)
      .setFeatureCorr(featureCorrelation)
      .setFeatureHistograms(featureHistograms)
      .setClusterAnalysis(clusterAnalysis)
      .setStatColumns(statColumns)
      .write()

    log.info(s"Training Dataset Stats updated Successfully")
  }

  /**
    * Hard coded settings for local spark training
    *
    * @return spark configurationh
    */
  def localSparkSetup(): SparkConf = {
    new SparkConf().setAppName("feature_engineering_spark").setMaster("local[*]")
  }

  /**
    * Hard coded settings for cluster spark training
    *
    * @return spark configuration
    */
  def sparkClusterSetup(): SparkConf = {
    new SparkConf().setAppName("feature_engineering_spark")
  }

}
