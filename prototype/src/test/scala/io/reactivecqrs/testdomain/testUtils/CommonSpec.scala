package io.reactivecqrs.testdomain.testUtils

import io.reactivecqrs.testdomain.spec.utils.ActorAskSupport
import org.scalatest.{FeatureSpecLike, GivenWhenThen, MustMatchers}

trait CommonSpec extends FeatureSpecLike with GivenWhenThen with ActorAskSupport with MustMatchers {

  def step(description: String): Unit = {
    // do nothing
  }

}
