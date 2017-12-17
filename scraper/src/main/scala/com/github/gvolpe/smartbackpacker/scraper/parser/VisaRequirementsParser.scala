package com.github.gvolpe.smartbackpacker.scraper.parser

import cats.Functor
import cats.effect.Sync
import cats.syntax.functor._
import com.github.gvolpe.smartbackpacker.model._
import com.github.gvolpe.smartbackpacker.scraper.model._
import com.github.gvolpe.smartbackpacker.scraper.config.ScraperConfiguration
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.model.{Document, Element}
import net.ruippeixotog.scalascraper.scraper.HtmlExtractor

import scala.util.{Failure, Success, Try}

class VisaRequirementsParser[F[_]](implicit F: Sync[F]) extends AbstractVisaRequirementsParser[F] {

  override def htmlDocument(from: CountryCode): F[Document] = {
    val ifEmpty: F[Document] = F.raiseError(WikiPageNotFound(from.value))
    ScraperConfiguration.wikiPage(from).fold(ifEmpty) { wikiPage =>
      F.delay {
        val browser = new JsoupBrowser()
        browser.get(wikiPage)
      }
    }
  }

}

abstract class AbstractVisaRequirementsParser[F[_] : Functor] {

  def htmlDocument(from: CountryCode): F[Document]

  def visaRequirementsFor(from: CountryCode): F[List[VisaRequirementsFor]] = {
    parseVisaRequirements(from).map { _.map { vr =>
      // If we can't find the code we just same the country name instead to easily trace the data
      val ifEmpty = VisaRequirementsFor(from, vr.to.value.as[CountryCode], vr.visaCategory, vr.description)
      countryCodeFor(vr.to).fold(ifEmpty)(VisaRequirementsFor(from, _, vr.visaCategory, vr.description))
    }}
  }

  private def countryCodeFor(name: CountryName): Option[CountryCode] = {
    ScraperConfiguration.countriesWithNames().find(_.names.map(_.value).contains(name.value)).map(_.code)
  }

  // To handle special cases like the Irish wiki page containing a table of both 4 and 5 columns
  private val wikiTableExtractor: HtmlExtractor[Element, Iterable[String]] = _.flatMap { e =>
    val sortTextSpan  = e >?> elementList(".sorttext")
    val sortText      = sortTextSpan.flatMap(_.headOption).map(_.text)
    val text          = sortText.getOrElse(e.text.split('!').head.trim) // for cases like Ivory Coast

    Try(e.attr("colspan")) match {
      case Success(cs) if cs == "2" => Seq(text, "")
      case _                        => Seq(text)
    }
  }

  private val colspanExtractor: HtmlExtractor[Element, Option[Element]] = _.find { e =>
    Try(e.attr("colspan")) match {
      case Success(cs) if cs == "2" => true
      case Success(_)               => true
      case Failure(_)               => false
    }
  }

  private val normalTableMapper: List[String] => VisaRequirementsParsing = {
    case (c :: v :: d :: x :: Nil) =>
      val extra = if (x == "X" || x == "√") "" else x
      VisaRequirementsParsing(c.noWhiteSpaces.as[CountryName], v.asVisaCategory, d.asDescription(extra))
    case (c :: v :: d :: Nil) =>
      VisaRequirementsParsing(c.noWhiteSpaces.as[CountryName], v.asVisaCategory, d.asDescription(""))
    case (c :: v :: Nil) =>
      VisaRequirementsParsing(c.noWhiteSpaces.as[CountryName], v.asVisaCategory, "")
    case (c :: Nil) =>
      VisaRequirementsParsing(c.noWhiteSpaces.as[CountryName], UnknownVisaCategory, "")
    case _ =>
      VisaRequirementsParsing("Not Found".as[CountryName], UnknownVisaCategory, "")
  }

  private val colspanTableMapper: List[String] => VisaRequirementsParsing = {
    case (c :: v :: d :: x :: _ :: Nil) =>
      VisaRequirementsParsing(c.noWhiteSpaces.as[CountryName], v.asVisaCategory, d.asDescription(x))
    case (c :: v :: d :: x :: Nil) =>
      VisaRequirementsParsing(c.noWhiteSpaces.as[CountryName], v.asVisaCategory, d.asDescription(x))
    case (c :: v :: d :: Nil) =>
      VisaRequirementsParsing(c.noWhiteSpaces.as[CountryName], v.asVisaCategory, d.asDescription(""))
    case (c :: v :: Nil) =>
      VisaRequirementsParsing(c.noWhiteSpaces.as[CountryName], v.asVisaCategory, "")
    case (c :: Nil) =>
      VisaRequirementsParsing(c.noWhiteSpaces.as[CountryName], UnknownVisaCategory, "")
    case _ =>
      VisaRequirementsParsing("Not Found".as[CountryName], UnknownVisaCategory, "")
  }

  private val parseColumns: Element => List[String] = e => {
    e.children.toList.flatMap(_ >> extractor("td", wikiTableExtractor))
  }

  private val rowspanMapper: List[Element] => List[String] = {
    case (x :: y :: xs) =>
      if (x.children.exists(_.hasAttr("rowspan"))) {
        val first  = parseColumns(x)
        val second = parseColumns(y)
        val lastCombined = first.lastOption.toList.map(_ ++ " " ++ second.mkString)
        // Drop the last element and append the combined one
        (first.dropRight(1) ::: lastCombined) ::: rowspanMapper(xs)
      } else {
        val noRowspan = parseColumns(x)
        noRowspan ::: rowspanMapper(y :: xs)
      }
    case (x :: xs) =>
      val noRowspan = parseColumns(x)
      noRowspan ::: rowspanMapper(xs)
    case _ =>
      List.empty[String]
  }

  // TODO: Aggregate ".sortable" table with ".wikitable" table that for some countries have partially recognized countries like Kosovo
  // TODO: This will require add more visa categories (See Polish page)
  private def parseVisaRequirements(from: CountryCode): F[List[VisaRequirementsParsing]] =
    htmlDocument(from).map { doc =>
      // Get first of all sortable tables
      val wikiTables = (doc >> elementList(".sortable")).headOption
      // Find out whether it's an irregular (colspan=2) or regular table
      val colspan = wikiTables.flatMap(_ >> extractor(".sortable td", colspanExtractor))
      // Find out the number of columns
      val tableSize = wikiTables.map { e => (e >> extractor(".sortable th", texts)).size }
      // Extract all the rows with visa information
      val table = wikiTables.toList.flatMap(_ >> extractor(".sortable tr", elementList))
      // Parse every row, extract the field and combine them whenever there's a rowspan
      val info  = rowspanMapper(table)

      // Group it per country using the corresponding mapper
      val mapper = colspan.fold(normalTableMapper)(_ => colspanTableMapper)
      val result = info.grouped(tableSize.getOrElse(3)).map(mapper).toList

      result
    }

}
