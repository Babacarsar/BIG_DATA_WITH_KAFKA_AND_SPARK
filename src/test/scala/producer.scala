import java.util.UUID
import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.javadsl.Producer
import akka.stream.scaladsl.Source
import org.apache.kafka.clients.producer.ProducerRecord
import com.google.gson.Gson
import org.apache.kafka.common.serialization.{Serializer, StringSerializer}
import scala.concurrent.duration._
import scala.util.Random
import java.util.concurrent.CompletionStage
import akka.Done

case class Transaction(
                        idTransaction: String,
                        typeTransaction: String,
                        montant: Double,
                        devise: String,
                        date: String,
                        lieu: String,
                        moyenPaiement: Option[String],
                        details: Map[String, Any],
                        utilisateur: Map[String, Any]
                      )

class JsonSerializer[T] extends Serializer[T] {
  private val gson = new Gson

  override def configure(configs: java.util.Map[String, _], isKey: Boolean): Unit = {}

  override def serialize(topic: String, data: T): Array[Byte] = gson.toJson(data).getBytes

  override def close(): Unit = {}
}

object ProducerApp extends App {
  implicit val system: ActorSystem = ActorSystem("ScalaKafkaProducer")

  val bootstrapServers = "localhost:9092"
  val topic = "transaction"

  val producerSettings =
    ProducerSettings(system, new StringSerializer, new JsonSerializer[Transaction])
      .withBootstrapServers(bootstrapServers)

  val paymentMethods = Seq("carte_de_credit", "especes", "virement_bancaire")
  val cities = Seq("Paris", "Marseille", "Lyon", "Toulouse", "Nice", "Nantes", "Strasbourg", "Montpellier", "Bordeaux", "Lille", "Rennes", "Reims", "Le Havre", "Saint-Étienne", "Toulon")
  val streets = Seq("Rue de la République", "Rue de Paris", "rue Auguste Delaune", "Rue Gustave Courbet", "Rue de Luxembourg", "Rue Fontaine", "Rue Zinedine Zidane", "Rue de Bretagne", "Rue Marceaux", "Rue Gambetta", "Rue du Faubourg Saint-Antoine", "Rue de la Grande Armée", "Rue de la Villette", "Rue de la Pompe", "Rue Saint-Michel")

  def generateTransaction(): Transaction = {
    val transactionTypes = Seq("achat", "remboursement", "transfert")
    val currentDateTime = java.time.LocalDateTime.now().toString

    Transaction(
      idTransaction = UUID.randomUUID().toString,
      typeTransaction = Random.shuffle(transactionTypes).head,
      montant = 10.0 + Random.nextDouble() * (1000.0 - 10.0),
      devise = "USD",
      date = currentDateTime,
      lieu = s"${Random.shuffle(cities).head}, ${Random.shuffle(streets).head}",
      moyenPaiement = if (Random.nextBoolean()) Some(Random.shuffle(paymentMethods).head) else None,
      details = Map(
        "produit" -> s"Produit${Random.nextInt(100)}",
        "quantite" -> Random.nextInt(10),
        "prixUnitaire" -> Random.nextInt(200)
      ),
      utilisateur = Map(
        "idUtilisateur" -> s"User${Random.nextInt(1000)}",
        "nom" -> s"Utilisateur${Random.nextInt(1000)}",
        "adresse" -> s"${Random.nextInt(1000)} ${Random.shuffle(streets).head}, ${Random.shuffle(cities).head}",
        "email" -> s"utilisateur${Random.nextInt(1000)}@example.com"
      )
    )
  }

  val transactionsSource = Source.tick(0.seconds, 1.second, ())
    .map(_ => generateTransaction())

  val producerSink = Producer.plainSink(producerSettings)

  transactionsSource
    .map { transaction =>
      // Affichage du message dans le terminal avant de l'envoyer
      println(s"Message envoyé : ${transaction}")
      new ProducerRecord[String, Transaction](topic, transaction.idTransaction, transaction)
    }
    .runWith(producerSink)
}
