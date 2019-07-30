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
import fr.acinq.bitcoin.{ByteVector32, Crypto, Transaction}
import grizzled.slf4j.Logging
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}
import scodec.bits._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class ElectrumClientSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with Logging with BeforeAndAfterAll {

  import ElectrumClient._

  var client: ActorRef = _
  val probe = TestProbe()
  // this is tx #2 of block #2683294
  val referenceTx = Transaction.read("02000000000104614a6036294d8b844e96aa1dd5d8896fa3ffa09f1315d0213269c600fe8646e2000000006a473044022025ea0e8fa96ddf278f3a12d67fec151988d676c158819caf4fa5265e5fd7fc5f02204b59c7a6c6ecc7b9c767b2ede2d0b3b12c186816b085026252f667e415c8c98e012102ff2d803e78629ebb85a3524553d3cd8371348e0b8a0f8dae82ad3ca4359294acfeffffffb701669d1a42a43aff48a6901b91782d00cc147e6c19911e9e0a9ad8f6c0fbae010000006a473044022062579557cd1418296c6cf2dbc143eeb37cc388f4e1c63587ff98c1c5b322842502206b0ad894f7757387d88666ec2f6b4756fafcf67b70c24aba313287997854310201210295aea4ca6889f1442ffc9225c6200c565e1ce4d2bc74112453c5f4cdb0eaa1a8feffffff5982be61f1378324c827187f941c4730d0103f0bd8538597a16681e5a4e63bc90200000017160014d4aac9490dc21cce338d7c795abdfe2925251894feffffff220b978c0d63acfca3778e1468ff8cc867940f4c34c55afcfff3915bb7120ba1010000001716001403aef4db154f9ccce3c5dcee0b10c58253d46f4ffeffffff0100f11788640000001976a9149ce52f3331649b976d943f61dde5e75008229ac788ac000002473044022040f147b4b402993880406012c042c3bbed80ca3a97bfe32a1dd896a4fcbe8bf7022052aee69956c54b7b56c567806d73e9f27a9775bf51c5e739d189ee6c045609e2012103a804561c62d8f0aa899d4f5a171f1d048ca7ae0e2c167682fc3d9c3fccdced0b02473044022008662cefe3950155821ffb55a113e6b19a29985811b498e26cbbee6884e22a0c0220158163e724ad0e1b9c5845cda16e771f724913e33360898d9de74618c0fe1678012103d36e94e0107461ff03cd0825e60e7d5fe431b0bf8384e6b4a528b04ac73426ca9df12800")
  val scriptHash = Crypto.sha256(referenceTx.txOut(0).publicKeyScript).reverse
  val height = 2683294
  val position = 1
  val merkleProof = List(
    hex"3c4f575a6286faf1506e2836c9f3b01701a8c782ead85dc55a4498baed3d1896",
    hex"5de4ccd0ac5050aae6a445f0382751377fe0bdf5b62228ce21953b29157a67d9",
    hex"736bafc8bde73e33fa7ddfabcfad32c1c0a9bea40cd6a44b4f23a468a7767307")
    .map(ByteVector32(_))

  override protected def beforeAll(): Unit = {
    client = system.actorOf(Props(new ElectrumClient(new InetSocketAddress("electrum1.groestlcoin.org", 50002), SSL.LOOSE)), "electrum-client")
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  test("connect to an electrumx mainnet server") {
    probe.send(client, AddStatusListener(probe.ref))
    probe.expectMsgType[ElectrumReady](15 seconds)
  }

  test("get transaction id from position") {
    probe.send(client, GetTransactionIdFromPosition(height, position))
    probe.expectMsg(GetTransactionIdFromPositionResponse(referenceTx.txid, height, position, Nil))
  }

  test("get transaction id from position with merkle proof") {
    probe.send(client, GetTransactionIdFromPosition(height, position, merkle = true))
    probe.expectMsg(GetTransactionIdFromPositionResponse(referenceTx.txid, height, position, merkleProof))
  }

  test("get transaction") {
    probe.send(client, GetTransaction(referenceTx.txid))
    val GetTransactionResponse(tx, _) = probe.expectMsgType[GetTransactionResponse]
    assert(tx == referenceTx)
  }

  test("get header") {
    probe.send(client, GetHeader(100000))
    val GetHeaderResponse(height, header) = probe.expectMsgType[GetHeaderResponse]
    assert(header.blockId == ByteVector32(hex"00000000012a42b54c21a7ada3b851c96a248348779b27db451cf6fda69916bb"))
  }

  test("get headers") {
    val start = (500000 / 2016) * 2016
    probe.send(client, GetHeaders(start, 2016))
    val GetHeadersResponse(start1, headers, _) = probe.expectMsgType[GetHeadersResponse]
    assert(start1 == start)
    assert(headers.size == 2016)
  }

  test("get merkle tree") {
    probe.send(client, GetMerkle(referenceTx.txid, 2683294))
    val response = probe.expectMsgType[GetMerkleResponse]
    assert(response.txid == referenceTx.txid)
    assert(response.block_height == 2683294)
    assert(response.pos == 1)
    assert(response.root == ByteVector32(hex"c0c6ddc8e2bbdcd71f4438607525882a59a62081a90a7f12b477554110fc28bf"))
  }

  test("header subscription") {
    val probe1 = TestProbe()
    probe1.send(client, HeaderSubscription(probe1.ref))
    val HeaderSubscriptionResponse(_, header) = probe1.expectMsgType[HeaderSubscriptionResponse]
    logger.info(s"received header for block ${header.blockId}")
  }

  test("scripthash subscription") {
    val probe1 = TestProbe()
    probe1.send(client, ScriptHashSubscription(scriptHash, probe1.ref))
    val ScriptHashSubscriptionResponse(scriptHash1, status) = probe1.expectMsgType[ScriptHashSubscriptionResponse]
    assert(status != "")
  }

  test("get scripthash history") {
    probe.send(client, GetScriptHashHistory(scriptHash))
    val GetScriptHashHistoryResponse(scriptHash1, history) = probe.expectMsgType[GetScriptHashHistoryResponse]
    assert(history.contains(TransactionHistoryItem(2683294, referenceTx.txid)))
  }

  test("list script unspents") {
    probe.send(client, ScriptHashListUnspent(scriptHash))
    val ScriptHashListUnspentResponse(scriptHash1, unspents) = probe.expectMsgType[ScriptHashListUnspentResponse]
    assert(unspents.isEmpty)
  }

}
