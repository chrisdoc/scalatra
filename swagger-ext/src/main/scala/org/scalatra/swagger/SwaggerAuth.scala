package org.scalatra
package swagger

import java.util.{Date => JDate, Locale}
import org.json4s._
import org.json4s.JsonDSL._
import ext.{ JodaTimeSerializers, EnumNameSerializer }
import org.joda.time._
import format.ISODateTimeFormat
import grizzled.slf4j.Logger
import org.scalatra.json.JsonSupport
import org.scalatra.auth.ScentrySupport

class SwaggerWithAuth(val swaggerVersion: String, val apiVersion: String) extends SwaggerEngine[AuthApi[AnyRef]] {
  private[this] val logger = Logger[this.type]
  /**
   * Registers the documentation for an API with the given path.
   */
  def register(name: String, path: String, description: String, s: SwaggerSupportSyntax with SwaggerSupportBase, listingPath: Option[String] = None) = {
    logger.debug("registering swagger api with: { name: %s, path: %s, description: %s, servlet: %s, listingPath: %s }" format (name, path, description, s.getClass, listingPath))
    _docs = _docs + (name -> AuthApi(path, listingPath, description, s.endpoints(path).map(_.asInstanceOf[AuthEndpoint[AnyRef]]), s.models))
  }
}

trait SwaggerAuthBase[TypeForUser <: AnyRef] extends SwaggerBaseBase { self: JsonSupport[_] with CorsSupport with ScentrySupport[TypeForUser] =>
  protected type ApiType = AuthApi[TypeForUser]
  protected implicit def swagger: SwaggerEngine[AuthApi[AnyRef]]
  protected def docToJson(doc: ApiType): JValue = doc.toJValue(userOption)
  
  before() {
    scentry.authenticate()    
  }

  abstract override def initialize(config: ConfigT) {
    super.initialize(config)

    get("/:doc(.:format)") {
      def isAllowed(doc: AuthApi[AnyRef]) = doc.apis.exists(_.operations.exists(_.allows(userOption)))
      swagger.doc(params("doc")) match {
        case Some(doc) if isAllowed(doc) ⇒ renderDoc(doc.asInstanceOf[ApiType])
        case _         ⇒ NotFound()
      }
    }

    get("/"+indexRoute+"(.:format)") {
      val docs = swagger.docs.filter(_.apis.exists(_.operations.exists(_.allows(userOption)))).toList
      if (docs.isEmpty) halt(NotFound())
      renderIndex(docs.asInstanceOf[List[ApiType]])
    }
  }

  override protected def renderDoc(doc: AuthApi[TypeForUser]): JValue = {
    doc.toJValue(userOption) merge
      ("basePath" -> fullUrl("/", includeServletPath = false)) ~
      ("swaggerVersion" -> swagger.swaggerVersion) ~
      ("apiVersion" -> swagger.apiVersion)
  }

  override protected def renderIndex(docs: List[ApiType]): JValue = {
    ("basePath" -> fullUrl("/", includeServletPath = false)) ~
      ("swaggerVersion" -> swagger.swaggerVersion) ~
      ("apiVersion" -> swagger.apiVersion) ~
      ("apis" ->
        (docs.toList map {
          doc => (("path" -> ((doc.listingPath getOrElse doc.resourcePath) + (if (includeFormatParameter) ".{format}" else ""))) ~
                 ("description" -> doc.description))
        }))
  }
  
}

case class AuthApi[TypeForUser <: AnyRef](resourcePath: String,
               listingPath: Option[String],
               description: String,
               apis: List[AuthEndpoint[TypeForUser]],
               models: Map[String, Model]) extends SwaggerApi[AuthEndpoint[TypeForUser]] {
  def toJValue[T >: TypeForUser <: TypeForUser](userOption: Option[T]) = AuthApi.toJValue(this, userOption)
}

object AuthApi {
  import SwaggerSerializers._

  lazy val Iso8601Date = ISODateTimeFormat.dateTime.withZone(DateTimeZone.UTC)

  private[this] def formats[T <: AnyRef](userOption: Option[T]) = new DefaultFormats {
    override val dateFormat = new DateFormat {
      def format(d: JDate) = new DateTime(d).toString(Iso8601Date)
      def parse(s: String) = try {
        Option(Iso8601Date.parseDateTime(s).toDate)
      } catch {
        case _ ⇒ None
      }
    }
  } ++ Seq(
    new EnumNameSerializer(ParamType),
    new HttpMethodSerializer,
    new ParameterSerializer,
    new AllowableValuesSerializer,
    new ModelFieldSerializer,
    new AuthOperationSerializer(userOption)) ++ JodaTimeSerializers.all

  def toJValue[T <: AnyRef](doc: Any, uOpt: Option[T]) = (Extraction.decompose(doc)(formats(uOpt)).noNulls)

  class AuthOperationSerializer[T <: AnyRef](userOption: Option[T]) extends Serializer[AuthOperation[T]] {

    def deserialize(implicit format: Formats): PartialFunction[(TypeInfo, _root_.org.json4s.JValue), AuthOperation[T]] = {
      case _ => null
    }

    def serialize(implicit format: Formats): PartialFunction[Any, _root_.org.json4s.JValue] = {
      case x: AuthOperation[T] if x.allows(userOption) =>        
        import Extraction.decompose
        ("httpMethod" -> decompose(x.httpMethod)) ~
        ("responseClass" -> x.responseClass) ~
        ("summary" -> x.summary) ~
        ("notes" -> x.notes) ~
        ("deprecated" -> x.deprecated) ~
        ("nickName" -> x.nickname) ~
        ("parameters" -> x.parameters.map(decompose)) ~
        ("errorResponses" -> x.errorResponses.map(decompose)) 
      case x: AuthOperation[_] => JNothing
    }
  }
}

case class AuthEndpoint[TypeForUser <: AnyRef](path: String,
												                    description: String,
												                    secured: Boolean = false,
												                    operations: List[AuthOperation[TypeForUser]] = Nil) extends SwaggerEndpoint[AuthOperation[TypeForUser]]

case class AuthOperation[TypeForUser <: AnyRef](httpMethod: HttpMethod,
												                     responseClass: String,
												                     summary: String,
												                     notes: Option[String] = None,
												                     deprecated: Boolean = false,
												                     nickname: Option[String] = None,
												                     parameters: List[Parameter] = Nil,
												                     errorResponses: List[Error] = Nil,
												                     allows: Option[TypeForUser] => Boolean = (_: Option[TypeForUser]) => true) extends SwaggerOperation

trait SwaggerAuthSupport[TypeForUser <: AnyRef] extends SwaggerSupportBase with SwaggerSupportSyntax { self: ScalatraBase with ScentrySupport[TypeForUser] =>
  protected def allows(value: Option[TypeForUser] => Boolean) = swaggerMeta(Symbols.Allows, value)
  
  private def allowAll = (u: Option[TypeForUser]) => true

  /**
   * Builds the documentation for all the endpoints discovered in an API.
   */
  def endpoints(basePath: String): List[AuthEndpoint[TypeForUser]] = {
    case class Entry(key: String, value: List[AuthOperation[TypeForUser]])
    val ops = (for {
      (method, routes) ← routes.methodRoutes
      route ← routes
      endpoint = route.metadata.get(Symbols.Endpoint) map (_.asInstanceOf[String]) getOrElse ""
      operation = operations(route, method)
      if (operation.nonEmpty && operation.head.nickname.isDefined)
    } yield {
      Entry(endpoint, operation)
    }) 
    (ops groupBy (_.key)).toList map { case (name, entries) =>
      val sec = entries.exists(_.value.exists(!_.allows.apply(None)))
      val desc = _description.lift apply name getOrElse ""
      val pth = if (basePath endsWith "/") basePath else basePath + "/"
      new AuthEndpoint[TypeForUser](pth + name, desc, sec, entries.toList flatMap (_.value))
    } sortBy (_.path)
  }
  /**
   * Returns a list of operations based on the given route. The default implementation returns a list with only 1
   * operation.
   */
  protected def operations(route: Route, method: HttpMethod): List[AuthOperation[TypeForUser]] = {
    val theParams = route.metadata.get(Symbols.Parameters) map (_.asInstanceOf[List[Parameter]]) getOrElse Nil
    val errors = route.metadata.get(Symbols.Errors) map (_.asInstanceOf[List[Error]]) getOrElse Nil
    val responseClass = route.metadata.get(Symbols.ResponseClass) map (_.asInstanceOf[String]) getOrElse DataType.Void.name
    val summary = (route.metadata.get(Symbols.Summary) map (_.asInstanceOf[String])).orNull
    val notes = route.metadata.get(Symbols.Notes) map (_.asInstanceOf[String])
    val nick = route.metadata.get(Symbols.Nickname) map (_.asInstanceOf[String])
    val allows = route.metadata.get(Symbols.Allows) map (_.asInstanceOf[Option[TypeForUser] => Boolean]) getOrElse allowAll
    List(AuthOperation[TypeForUser](httpMethod = method,
      responseClass = responseClass,
      summary = summary,
      notes = notes,
      nickname = nick,
      parameters = theParams,
      errorResponses = errors ::: swaggerDefaultErrors,
      allows = allows))
  }

}