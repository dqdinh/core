package com.github.gvolpe.smartbackpacker.http

import cats.effect.IO
import com.github.gvolpe.smartbackpacker.http.ResponseBodyUtils._
import com.github.gvolpe.smartbackpacker.model._
import com.github.gvolpe.smartbackpacker.persistence.AirlineDao
import com.github.gvolpe.smartbackpacker.service.AirlineService
import org.http4s.{HttpService, Query, Request, Response, Status, Uri}
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FlatSpecLike, Matchers}

class AirlinesHttpEndpointSpec extends FlatSpecLike with Matchers with AirlinesHttpEndpointFixture {

  forAll(examples) { (airline, expectedStatus, expectedBody) =>
    it should s"find the airline $airline" in {
      val request = Request[IO](uri = Uri(path = s"/airlines", query = Query(("name", Some(airline)))))

      val task: Option[Response[IO]] = httpService(request).value.unsafeRunSync()
      task should not be None
      task.foreach { response =>
        response.status should be (expectedStatus)
        assert(response.body.asString.contains(expectedBody))
      }
    }
  }

}

trait AirlinesHttpEndpointFixture extends PropertyChecks {

  private val airlines: List[Airline] = List(
    Airline("Aer Lingus".as[AirlineName], BaggagePolicy(
      allowance = List(
        BaggageAllowance(CabinBag, Some(10), BaggageSize(55, 40, 24)),
        BaggageAllowance(SmallBag, None, BaggageSize(25, 33, 20))
      ),
      extra = None,
      website = Some("https://www.aerlingus.com/travel-information/baggage-information/cabin-baggage/"))
    ),
    Airline("Transavia".as[AirlineName], BaggagePolicy(
      allowance = List(
        BaggageAllowance(CabinBag, None, BaggageSize(55, 40, 25))
      ),
      extra = None,
      website = Some("https://www.transavia.com/en-EU/service/hand-luggage/"))
    )
  )

  private val testAirlineDao = new AirlineDao[IO] {
    override def findAirline(airlineName: AirlineName): IO[Option[Airline]] = IO {
      airlines.find(_.name.value == airlineName.value)
    }
  }

  val httpService: HttpService[IO] =
    new AirlinesHttpEndpoint(
      new AirlineService[IO](testAirlineDao)
    ).service

  val examples = Table(
    ("airline", "expectedStatus", "expectedBody"),
    ("Aer Lingus", Status.Ok, "baggagePolicy"),
    ("Transavia", Status.Ok, "baggagePolicy"),
    ("Ryan Air", Status.BadRequest, "Airline not found")
  )

}