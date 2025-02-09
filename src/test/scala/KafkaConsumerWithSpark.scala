import org.apache.spark.sql.{SparkSession, DataFrame, functions => F}
import org.apache.spark.sql.types.{StringType, StructType, DoubleType, TimestampType}
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.functions._

object KafkaConsumerWithSpark extends App {

  val spark = SparkSession.builder()
    .appName("KafkaConsumerWithSpark")
    .master("local[*]")
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
    .withColumn("montant", when(col("devise") === "USD", col("montant").cast(DoubleType) * 0.92) // Conversion USD → EUR
      .otherwise(col("montant").cast(DoubleType))) // Sinon, garde la valeur d'origine
    .withColumn("devise", lit("EUR")) // Uniformisation en EUR
    .withColumn("date", to_timestamp(col("date"), "yyyy-MM-dd HH:mm:ss")) // Conversion de date
    .withColumn("date", from_utc_timestamp(col("date"), "Europe/Paris")) // Ajout du fuseau horaire
    .filter(col("montant").isNotNull && col("montant") > 0) // Suppression des transactions en erreur
    .filter(col("lieu").isNotNull) // Suppression des valeurs None dans l’adresse

  // Écriture du stream avec limite de 10 messages
  val query = transactionsStream
    .limit(10)
    .writeStream
    .format("parquet")
    .option("path", "s3a://transaction/transactions")
    .option("checkpointLocation", "s3a://transaction/transactions/checkpoint")
    .trigger(Trigger.Once()) // Exécuter une seule micro-batch et s'arrêter
    .start()

  query.awaitTermination()

  // Lecture des données stockées en Parquet sur MinIO
  val parquetPath = "s3a://transaction/transactions"
  val transactionsDF: DataFrame = spark.read.parquet(parquetPath)

  transactionsDF.show()

  spark.stop()
}
