import org.apache.spark.sql.{SparkSession, DataFrame, functions => F}
import org.apache.spark.sql.types.{StringType, StructType, DoubleType, TimestampType}
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.functions._

object ScalaToMinio {
  def main(args: Array[String]): Unit = {

    val spark = SparkSession.builder()
      .appName("KafkaConsumerWithSpark")
      .master("local[*]")  // Exécution en mode local
      .config("spark.hadoop.fs.s3a.endpoint", "http://localhost:9000")
      .config("spark.hadoop.fs.s3a.access.key", "minio")
      .config("spark.hadoop.fs.s3a.secret.key", "minio123")
      .config("spark.hadoop.fs.s3a.path.style.access", "true")
      .config("spark.hadoop.fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
      .getOrCreate()

    val kafkaBootstrapServers = "localhost:9092"
    val kafkaTopic = "transaction"

    val kafkaOptions = Map(
      "kafka.bootstrap.servers" -> kafkaBootstrapServers,
      "subscribe" -> kafkaTopic,
      "startingOffsets" -> "earliest"
    )

    val transactionSchema = new StructType()
      .add("idTransaction", StringType)
      .add("typeTransaction", StringType)
      .add("montant", StringType)
      .add("devise", StringType)
      .add("date", StringType)
      .add("lieu", StringType)
      .add("moyenPaiement", StringType)
      .add("details", StringType)
      .add("utilisateur", StringType)

    val rawStream = spark.readStream
      .format("kafka")
      .options(kafkaOptions)
      .load()

    val transactionsStream = rawStream
      .selectExpr("CAST(value AS STRING) AS json")
      .select(F.from_json(F.col("json"), transactionSchema).as("transaction"))
      .select("transaction.*")
      .withColumn("montant", when(col("devise") === "USD", col("montant").cast(DoubleType) * 0.92)
        .otherwise(col("montant").cast(DoubleType))) // Conversion en EUR
      .withColumn("devise", lit("EUR")) // Uniformisation en EUR
      .withColumn("date", to_timestamp(col("date"), "yyyy-MM-dd HH:mm:ss")) // Conversion de date
      .withColumn("date", from_utc_timestamp(col("date"), "Europe/Paris")) // Fuseau horaire Paris
      .filter(col("montant").isNotNull && col("montant") > 0) // Suppression des erreurs
      .filter(col("lieu").isNotNull) // Suppression des valeurs nulles

    // Chemins pour MinIO (S3A)
    val minioParquetPath = "s3a://bigdata/transactions"
    val checkpointPath = "s3a://bigdata/transactions/checkpoint"

    // Écriture du stream en format Parquet sur MinIO
    val query = transactionsStream
      .writeStream
      .format("parquet")
      .option("path", minioParquetPath) // Stockage Parquet sur MinIO
      .option("checkpointLocation", checkpointPath) // Checkpoints sur MinIO
      .option("compression", "snappy") // Compression pour optimiser l’espace
      .trigger(Trigger.Once())  // Exécuter toutes les 30s
      .start()

    query.awaitTermination()

    // Lecture des données stockées sur MinIO
    val transactionsDF: DataFrame = spark.read.parquet(minioParquetPath)

    transactionsDF.show()

    spark.stop()
  }
}