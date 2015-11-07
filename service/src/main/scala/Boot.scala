package lv.addresses.service

import akka.actor.{ ActorSystem, Props, ActorRef, Actor, ActorLogging }

import scala.util.Try
import scala.language.postfixOps
import scala.concurrent.duration._
import scala.collection.JavaConversions._

import spray.json._

import com.typesafe.config._

import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes._

object MyJsonProtocol extends DefaultJsonProtocol {
  implicit val f01 = jsonFormat20(AddressFull)
  implicit val f14 = jsonFormat14(lv.addresses.indexer.AddressStruct)
}

case class AddressFull(
  code: Int, address: String, zipCode: Option[String], typ: Int,
  coordX: Option[BigDecimal], coordY: Option[BigDecimal],
  pilCode: Option[Int] = None, pilName: Option[String] = None,
  novCode: Option[Int] = None, novName: Option[String] = None,
  pagCode: Option[Int] = None, pagName: Option[String] = None,
  cieCode: Option[Int] = None, cieName: Option[String] = None,
  ielCode: Option[Int] = None, ielName: Option[String] = None,
  nltCode: Option[Int] = None, nltName: Option[String] = None,
  dzvCode: Option[Int] = None, dzvName: Option[String] = None)

import MyJsonProtocol._
import AddressService._

trait AddressHttpService {

  val CODE_PATTERN = "(\\d{9,})"r

  val wsVersionNofifications =
    Flow.wrap(FlowGraph.partial(Source.actorRef[Version](0, OverflowStrategy.fail)
      .map(v => TextMessage.Strict(normalizeVersion(v.version)))) {
      import FlowGraph.Implicits._
      implicit builder => src =>
        val M = builder.add(Merge[Message](2))
        src ~> M
        FlowShape(M.in(1), M.out)
    }).mapMaterializedValue (actor => subscribe(actor, "version"))

  val route =
    handleWebsocketMessages(wsVersionNofifications) ~ path("") {
      redirect("/index.html", SeeOther)
    } ~ path("index.html") {
      getFromResource("index.html")
    } ~ (path("address") & get & parameterMultiMap) { params =>
      val pattern = params.get("search") map (_.head) getOrElse ("")
      val limit = params.get("limit") map (_.head.toInt) getOrElse 20
      val types = params.get("type").map(_.toSet.map((t: String) => t.toInt)).orNull
      complete(
        (for {
          f <- finder
          s <- (pattern match {
            case CODE_PATTERN(code) => address(code.toInt) map (_.toArray)
            case p => search(p, limit, types)
          })
        } yield {
          s map { a =>
            val st = f.addressStruct(a.code)
            import st._
            AddressFull(a.code, a.address, Option(a.zipCode), a.typ,
              Option(a.coordX), Option(a.coordY),
              pilCode, pilName,
              novCode, novName,
              pagCode, pagName,
              cieCode, cieName,
              ielCode, ielName,
              nltCode, nltName,
              dzvCode, dzvName)
          }
        }) map { _.toJson.prettyPrint })
    } ~ (path("address-structure" / IntNumber)) { code =>
      complete(struct(code) map (_.toJson.prettyPrint))
    } ~ path("version") {
      complete(version map (normalizeVersion(_)))
    } ~ pathSuffixTest(""".*(\.js|\.css|\.html|\.png|\.gif|\.jpg|\.jpeg|\.svg|\.woff|\.ttf|\.woff2)$"""r) { p => //static web resources TODO - make extensions configurable
      path(Rest) { resource => getFromResource(resource) }
    }
}

object Boot extends scala.App with AddressHttpService {

  val conf = com.typesafe.config.ConfigFactory.load

  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem("address-service")
  implicit val materializer = ActorMaterializer()

  AddressService.maybeInit
  FTPDownload.initialize
  val bindingFuture = Http().bindAndHandle(route, "localhost",
    scala.util.Try(conf.getInt("address-service-port")).toOption.getOrElse(8082))
}
