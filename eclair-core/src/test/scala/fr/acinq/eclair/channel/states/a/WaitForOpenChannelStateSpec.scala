/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel.states.a

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire.{Error, Init, OpenChannel}
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, TestConstants, TestkitBaseClass, ToMilliSatoshiConversion}
import org.scalatest.Outcome

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForOpenChannelStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  case class FixtureParam(bob: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, bob2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    val channelVersion = ChannelVersion.STANDARD
    val (aliceParams, bobParams) = (Alice.channelParams, Bob.channelParams)
    val aliceInit = Init(aliceParams.features)
    val bobInit = Init(bobParams.features)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Empty, channelVersion)
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, bobParams, bob2alice.ref, aliceInit)
      awaitCond(bob.stateName == WAIT_FOR_OPEN_CHANNEL)
      withFixture(test.toNoArgTest(FixtureParam(bob, alice2bob, bob2alice, bob2blockchain)))
    }
  }

  test("recv OpenChannel") { f =>
    import f._
    alice2bob.expectMsgType[OpenChannel]
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
  }

  test("recv OpenChannel (invalid chain)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    // using livenet genesis block
    val livenetChainHash = Block.LivenetGenesisBlock.hash
    bob ! open.copy(chainHash = livenetChainHash)
    val error = bob2alice.expectMsgType[Error]
    assert(error === Error(open.temporaryChannelId, InvalidChainHash(open.temporaryChannelId, Block.RegtestGenesisBlock.hash, livenetChainHash).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (funding too low)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val lowFunding = 100.sat
    bob ! open.copy(fundingSatoshis = lowFunding)
    val error = bob2alice.expectMsgType[Error]
    assert(error === Error(open.temporaryChannelId, InvalidFundingAmount(open.temporaryChannelId, lowFunding, Bob.nodeParams.minFundingSatoshis, Channel.MAX_FUNDING).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (funding too high)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val highFundingMsat = 100000000.sat
    bob ! open.copy(fundingSatoshis = highFundingMsat)
    val error = bob2alice.expectMsgType[Error]
    assert(error.toAscii === Error(open.temporaryChannelId, InvalidFundingAmount(open.temporaryChannelId, highFundingMsat, Bob.nodeParams.minFundingSatoshis, Channel.MAX_FUNDING).getMessage).toAscii)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (invalid max accepted htlcs)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val invalidMaxAcceptedHtlcs = Channel.MAX_ACCEPTED_HTLCS + 1
    bob ! open.copy(maxAcceptedHtlcs = invalidMaxAcceptedHtlcs)
    val error = bob2alice.expectMsgType[Error]
    assert(error === Error(open.temporaryChannelId, InvalidMaxAcceptedHtlcs(open.temporaryChannelId, invalidMaxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (invalid push_msat)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val invalidPushMsat = 100000000000L.msat
    bob ! open.copy(pushMsat = invalidPushMsat)
    val error = bob2alice.expectMsgType[Error]
    assert(error === Error(open.temporaryChannelId, InvalidPushAmount(open.temporaryChannelId, invalidPushMsat, open.fundingSatoshis.toMilliSatoshi).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (to_self_delay too high)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val delayTooHigh = CltvExpiryDelta(10000)
    bob ! open.copy(toSelfDelay = delayTooHigh)
    val error = bob2alice.expectMsgType[Error]
    assert(error === Error(open.temporaryChannelId, ToSelfDelayTooHigh(open.temporaryChannelId, delayTooHigh, Alice.nodeParams.maxToLocalDelayBlocks).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (reserve too high)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    // 30% is huge, recommended ratio is 1%
    val reserveTooHigh = TestConstants.fundingSatoshis * 0.3
    bob ! open.copy(channelReserveSatoshis = reserveTooHigh)
    val error = bob2alice.expectMsgType[Error]
    assert(error === Error(open.temporaryChannelId, ChannelReserveTooHigh(open.temporaryChannelId, reserveTooHigh, 0.3, 0.05).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (fee too low, but still valid)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    // set a very small fee
    val tinyFee = 253
    bob ! open.copy(feeratePerKw = tinyFee)
    val error = bob2alice.expectMsgType[Error]
    // we check that the error uses the temporary channel id
    assert(error === Error(open.temporaryChannelId, "local/remote feerates are too different: remoteFeeratePerKw=253 localFeeratePerKw=10000"))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (fee below absolute valid minimum)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    // set a very small fee
    val tinyFee = 252
    bob ! open.copy(feeratePerKw = tinyFee)
    val error = bob2alice.expectMsgType[Error]
    // we check that the error uses the temporary channel id
    assert(error === Error(open.temporaryChannelId, "remote fee rate is too small: remoteFeeratePerKw=252"))
    awaitCond(bob.stateName == CLOSED)
  }


  test("recv OpenChannel (reserve below dust)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val reserveTooSmall = open.dustLimitSatoshis - 1.sat
    bob ! open.copy(channelReserveSatoshis = reserveTooSmall)
    val error = bob2alice.expectMsgType[Error]
    // we check that the error uses the temporary channel id
    assert(error === Error(open.temporaryChannelId, DustLimitTooLarge(open.temporaryChannelId, open.dustLimitSatoshis, reserveTooSmall).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (toLocal + toRemote below reserve)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val fundingSatoshis = open.channelReserveSatoshis + 499.sat
    val pushMsat = (500 sat).toMilliSatoshi
    bob ! open.copy(fundingSatoshis = fundingSatoshis, pushMsat = pushMsat)
    val error = bob2alice.expectMsgType[Error]
    // we check that the error uses the temporary channel id
    assert(error === Error(open.temporaryChannelId, ChannelReserveNotMet(open.temporaryChannelId, pushMsat, (open.channelReserveSatoshis - 1.sat).toMilliSatoshi, open.channelReserveSatoshis).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv Error") { f =>
    import f._
    bob ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    bob ! CMD_CLOSE(None)
    awaitCond(bob.stateName == CLOSED)
  }

}
