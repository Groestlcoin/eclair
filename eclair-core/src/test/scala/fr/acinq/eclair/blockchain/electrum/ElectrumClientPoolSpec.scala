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

package fr.acinq.eclair.blockchain.electrum

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import fr.acinq.bitcoin.{ByteVector32, Crypto, Transaction}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._
import grizzled.slf4j.Logging
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}
import scodec.bits._

import scala.concurrent.duration._
import scala.util.Random


class ElectrumClientPoolSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with Logging with BeforeAndAfterAll {
  var pool: ActorRef = _
  val probe = TestProbe()
  // this is tx #2690 of block #500000
  val referenceTx = Transaction.read("02000000000104614a6036294d8b844e96aa1dd5d8896fa3ffa09f1315d0213269c600fe8646e2000000006a473044022025ea0e8fa96ddf278f3a12d67fec151988d676c158819caf4fa5265e5fd7fc5f02204b59c7a6c6ecc7b9c767b2ede2d0b3b12c186816b085026252f667e415c8c98e012102ff2d803e78629ebb85a3524553d3cd8371348e0b8a0f8dae82ad3ca4359294acfeffffffb701669d1a42a43aff48a6901b91782d00cc147e6c19911e9e0a9ad8f6c0fbae010000006a473044022062579557cd1418296c6cf2dbc143eeb37cc388f4e1c63587ff98c1c5b322842502206b0ad894f7757387d88666ec2f6b4756fafcf67b70c24aba313287997854310201210295aea4ca6889f1442ffc9225c6200c565e1ce4d2bc74112453c5f4cdb0eaa1a8feffffff5982be61f1378324c827187f941c4730d0103f0bd8538597a16681e5a4e63bc90200000017160014d4aac9490dc21cce338d7c795abdfe2925251894feffffff220b978c0d63acfca3778e1468ff8cc867940f4c34c55afcfff3915bb7120ba1010000001716001403aef4db154f9ccce3c5dcee0b10c58253d46f4ffeffffff0100f11788640000001976a9149ce52f3331649b976d943f61dde5e75008229ac788ac000002473044022040f147b4b402993880406012c042c3bbed80ca3a97bfe32a1dd896a4fcbe8bf7022052aee69956c54b7b56c567806d73e9f27a9775bf51c5e739d189ee6c045609e2012103a804561c62d8f0aa899d4f5a171f1d048ca7ae0e2c167682fc3d9c3fccdced0b02473044022008662cefe3950155821ffb55a113e6b19a29985811b498e26cbbee6884e22a0c0220158163e724ad0e1b9c5845cda16e771f724913e33360898d9de74618c0fe1678012103d36e94e0107461ff03cd0825e60e7d5fe431b0bf8384e6b4a528b04ac73426ca9df12800")
  val scriptHash = Crypto.sha256(referenceTx.txOut(0).publicKeyScript).reverse
  val serverAddresses = {
    val stream = classOf[ElectrumClientSpec].getResourceAsStream("/electrum/servers_mainnet.json")
    val addresses = ElectrumClientPool.readServerAddresses(stream, sslEnabled = false)
    stream.close()
    addresses
  }

  implicit val timeout = 20 seconds

  import concurrent.ExecutionContext.Implicits.global

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  test("pick a random, unused server address") {
    val usedAddresses = Random.shuffle(serverAddresses.toSeq).take(serverAddresses.size / 2).map(_.adress).toSet
    for(_ <- 1 to 10) {
      val Some(pick) = ElectrumClientPool.pickAddress(serverAddresses, usedAddresses)
      assert(!usedAddresses.contains(pick.adress))
    }
  }

  test("init an electrumx connection pool") {
    val random = new Random()
    val stream = classOf[ElectrumClientSpec].getResourceAsStream("/electrum/servers_mainnet.json")
    val addresses = random.shuffle(serverAddresses.toSeq).take(2).toSet + ElectrumClientPool.ElectrumServerAddress(new InetSocketAddress("electrum.acinq.co", 50002), SSL.STRICT)
    stream.close()
    assert(addresses.nonEmpty)
    pool = system.actorOf(Props(new ElectrumClientPool(addresses)), "electrum-client")
  }

  test("connect to an electrumx mainnet server") {
    probe.send(pool, AddStatusListener(probe.ref))
    // make sure our master is stable, if the first master that we select is behind the other servers we will switch
    // during the first few seconds
    awaitCond({
      probe.expectMsgType[ElectrumReady](30 seconds)
      probe.receiveOne(5 seconds) == null
    }, max = 60 seconds, interval = 1000 millis)  }

  test("get transaction") {
    probe.send(pool, GetTransaction(referenceTx.txid))
    val GetTransactionResponse(tx, _) = probe.expectMsgType[GetTransactionResponse](timeout)
    assert(tx == referenceTx)
  }

  test("get merkle tree") {
    probe.send(pool, GetMerkle(referenceTx.txid, 2683294))
    val response = probe.expectMsgType[GetMerkleResponse](timeout)
    assert(response.txid == referenceTx.txid)
    assert(response.block_height == 2683294)
    assert(response.pos == 1)
    assert(response.root == ByteVector32(hex"c0c6ddc8e2bbdcd71f4438607525882a59a62081a90a7f12b477554110fc28bf"))
  }

  test("header subscription") {
    val probe1 = TestProbe()
    probe1.send(pool, HeaderSubscription(probe1.ref))
    val HeaderSubscriptionResponse(_, header) = probe1.expectMsgType[HeaderSubscriptionResponse](timeout)
    logger.info(s"received header for block ${header.blockId}")
  }

  test("scripthash subscription") {
    val probe1 = TestProbe()
    probe1.send(pool, ScriptHashSubscription(scriptHash, probe1.ref))
    val ScriptHashSubscriptionResponse(scriptHash1, status) = probe1.expectMsgType[ScriptHashSubscriptionResponse](timeout)
    assert(status != "")
  }

  test("get scripthash history") {
    probe.send(pool, GetScriptHashHistory(scriptHash))
    val GetScriptHashHistoryResponse(scriptHash1, history) = probe.expectMsgType[GetScriptHashHistoryResponse](timeout)
    assert(history.contains((TransactionHistoryItem(2683294, referenceTx.txid))))
  }

  test("list script unspents") {
    probe.send(pool, ScriptHashListUnspent(scriptHash))
    val ScriptHashListUnspentResponse(scriptHash1, unspents) = probe.expectMsgType[ScriptHashListUnspentResponse](timeout)
    assert(unspents.isEmpty)
  }
}
