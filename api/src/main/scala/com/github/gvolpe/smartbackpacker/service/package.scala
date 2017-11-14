package com.github.gvolpe.smartbackpacker

package object service {

  case class CurrencyExchangeDTO(base: String, date: String, rates: Map[String, Double])

  object CountriesMustBeDifferent extends Exception("Countries must be different!")

}