/**
	* Copyright 2016 ZuInnoTe (Jörn Franke) <zuinnote@gmail.com>
	*
	* Licensed under the Apache License, Version 2.0 (the "License");
	* you may not use this file except in compliance with the License.
	* You may obtain a copy of the License at
	*
	*    http://www.apache.org/licenses/LICENSE-2.0
	*
	* Unless required by applicable law or agreed to in writing, software
	* distributed under the License is distributed on an "AS IS" BASIS,
	* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	* See the License for the specific language governing permissions and
	* limitations under the License.
	**/

package org.zuinnote.spark.bitcoin.example


import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.hadoop.conf._
import org.apache.spark.sql._
import org.apache.spark.sql.types._
import org.apache.spark.graphx._
//import org.apache.spark.sql.SQLContext.implicits._
//import SQLContext.implicits._
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.io._

import org.zuinnote.hadoop.bitcoin.format.common._
import org.zuinnote.hadoop.bitcoin.format.mapreduce._


/**
	* Author: Jörn Franke <zuinnote@gmail.com>
	*
	*/

/**

	*
	* Constructs a graph out of Bitcoin Transactions.
	*
	* Vertex: Bitcoin address (only in the old public key and the new P2P hash format)
	* Edge: A directed edge from one source Bitcoin address to a destination Bitcoin address (A link between the input of one transaction to the originating output of another transaction via the transaction hash and input/output indexes)
	*
	* Problem to solve: Find the top 5 bitcoin addresses with the highest number of inputs from other bitcoin addresses
	*/

object SparkScalaBitcoinTransactionGraph {
	def main(args: Array[String]): Unit = {
		val conf = new SparkConf().setAppName("Spark-Scala-Graphx BitcoinTransaction Graph (hadoopcryptoledger)")
		val sc=new SparkContext(conf)
		val hadoopConf = new Configuration();
		val spark = org.apache.spark.sql.SparkSession.builder()
			.getOrCreate()

		import spark.implicits._
		hadoopConf.set("hadoopcryptoledger.bitcoinblockinputformat.filter.magic","F9BEB4D9");
		jobTop5AddressInput(sc,hadoopConf,args(0),args(1))
		sc.stop()
	}

	def jobTop5AddressInput(sc: SparkContext, hadoopConf: Configuration, inputFile: String, outputFile: String): Unit = {
		val bitcoinBlocksRDD = sc.newAPIHadoopFile(inputFile, classOf[BitcoinBlockFileInputFormat], classOf[BytesWritable], classOf[BitcoinBlock],hadoopConf)
		// extract a tuple per transaction containing Bitcoin destination address, the input transaction hash, the input transaction output index, and the current transaction hash, the current transaction output index, a (generated) long identifier
		val bitcoinTransactionTuples = bitcoinBlocksRDD.flatMap(hadoopKeyValueTuple => extractTransactionData(hadoopKeyValueTuple._2))

		val rowRDD = bitcoinTransactionTuples.map(p => Row(p._1, p._2, p._3, p._4, p._5, p._6,p._7))

		val transactionSchema = StructType(
			Array(
				StructField("dest_address", StringType, true),
				StructField("curr_trans_input_hash", BinaryType, false),
				StructField("curr_trans_input_output_idx", LongType, false),
				StructField("curr_trans_hash", BinaryType, false),
				StructField("curr_trans_output_idx", LongType, false),
				StructField("timestamp", IntegerType, false),
				StructField("value",LongType,false)
			)
		)
		val sqlContext= new SQLContext(sc)
		//import sqlContext.implicits._
		import sqlContext.implicits._
		val btcDF = sqlContext.createDataFrame(rowRDD, transactionSchema)
		var centralTranscations=btcDF.filter($"dest_address".equalTo("bitcoinaddress_99BC78BA577A95A11F1A344D4D2AE55F2F857B98"))
		centralTranscations.show(10)
		val outputSourceNames=Seq("dest_address","curr_trans_input_hash","curr_trans_input_output_idx","curr_trans_hash","curr_trans_output_idx","timestamp","value")
		val inputSourceNames=Seq("source_address","source_trans_input_hash","source_trans_input_output_idx","source_trans_hash","source_trans_output_idx","source_timestamp","source_value")
		//btcDF.show(10)
		val sourceDF=btcDF.select(outputSourceNames:_*).toDF(inputSourceNames:_*)
		val joined_degree1=centralTranscations.join(sourceDF,centralTranscations("curr_trans_input_hash")===sourceDF("source_trans_hash")&&centralTranscations("curr_trans_input_output_idx")===sourceDF("source_trans_output_idx"))
		joined_degree1.show(10)
		joined_degree1.select($"dest_address",$"value",$"source_address",$"source_value").distinct.write.format("com.databricks.spark.csv").option("header", "true").save(outputFile+"/degree1.csv")


//		val source_degree1=joined_degree1.select($"source_address".alias("dest_address"), $"source_trans_input_hash".alias("curr_trans_input_hash"),$"source_trans_input_output_idx".alias("curr_trans_input_output_idx"),$"source_trans_hash".alias("curr_trans_input_hash"), $"source_trans_output_idx".alias("curr_trans_output_idx"),$"source_timestamp".alias("timestamp"),$"source_value".alias("value"))
//		val joined_degree2=source_degree1.join(sourceDF,centralTranscations("curr_trans_input_hash")===sourceDF("source_trans_hash")&&centralTranscations("curr_trans_input_output_idx")===sourceDF("source_trans_output_idx"))
//		joined_degree2.show(10)
//		joined_degree2.select($"dest_address",$"value",$"source_address",$"source_value").distinct.write.format("com.databricks.spark.csv").option("header", "true").save(outputFile+"/degree2.csv")
//
//
//		val source_degree2=joined_degree2.select($"source_address".alias("dest_address"), $"source_trans_input_hash".alias("curr_trans_input_hash"),$"source_trans_input_output_idx".alias("curr_trans_input_output_idx"),$"source_trans_hash".alias("curr_trans_input_hash"), $"source_trans_output_idx".alias("curr_trans_output_idx"),$"source_timestamp".alias("timestamp"),$"source_value".alias("value"))
//		val joined_degree3=source_degree2.join(sourceDF,centralTranscations("curr_trans_input_hash")===sourceDF("source_trans_hash")&&centralTranscations("curr_trans_input_output_idx")===sourceDF("source_trans_output_idx"))
//		joined_degree2.show(10)
//		joined_degree2.select($"dest_address",$"value",$"source_address",$"source_value").distinct.write.format("com.databricks.spark.csv").option("header", "true").save(outputFile+"/degree3.csv")
//		// create the vertex (vertexId, Bitcoin destination address), keep in mind that the flat table contains the same bitcoin address several times
//			val bitcoinAddressIndexed = bitcoinTransactionTuples.map(bitcoinTransactions =>bitcoinTransactions._1).distinct().zipWithIndex()
//			// create the edges. Basically we need to determine which inputVertexId refers to which outputVertex Id.
//			// This is basically a self join, where ((currentTransactionHash,currentOutputIndex), identfier) is joined with ((inputTransactionHash,currentinputIndex), indentifier)
//			// a self join consumes a lot of resources, however there is no other way to create the graph
//			// for production systems the following is recommended
//			// 1) save bitcoinTransactionTuples to a file
//			// 2) save the self-joined data to a file
//			// 3) if new data arrives:
//			// 	append new data bitcoinTransactionTuples and make sure that subsequent identifiers are generated for them
//			//	get the generated identifiers for the new data together with the corresponding new data
//			//	join the new data with bitcoinTransactionTuples
//			//	append the joined data to the self-joined data in the file
//			//	join the new vertices with the old graph in-memory
//			// 	rerun any algorithm
//			// (bitcoinAddress,(byteArrayTransaction, TransactionIndex)
//			val inputTransactionTuple =  bitcoinTransactionTuples.map(bitcoinTransactions => (bitcoinTransactions._1,(new ByteArray(bitcoinTransactions._2),bitcoinTransactions._3)))
//			// (bitcoinAddress,((byteArrayTransaction, TransactionIndex),vertexid))
//			val inputTransactionTupleWithIndex = inputTransactionTuple.join(bitcoinAddressIndexed)
//			// (byteArrayTransaction, TransactionIndex), (vertexid, bitcoinAddress)
//			val inputTransactionTupleByHashIdx = inputTransactionTupleWithIndex.map(iTTuple => (iTTuple._2._1,(iTTuple._2._2,iTTuple._1)))
//			val currentTransactionTuple =  bitcoinTransactionTuples.map(bitcoinTransactions => (bitcoinTransactions._1,(new ByteArray(bitcoinTransactions._4),bitcoinTransactions._5)))
//
//			val currentTransactionTupleWithIndex = currentTransactionTuple.join(bitcoinAddressIndexed)
//			// (byteArrayTransaction, TransactionIndex), (vertexid, bitcoinAddress)
//			val currentTransactionTupleByHashIdx = currentTransactionTupleWithIndex.map{cTTuple => (cTTuple._2._1,(cTTuple._2._2,cTTuple._1))}
//			// the join creates ((ByteArray, Idx), (srcIdx,srcAdress), (destIdx,destAddress)
//			val joinedTransactions = inputTransactionTupleByHashIdx.join(currentTransactionTupleByHashIdx)
//			// create vertices => vertexId,bitcoinaddress
//			val bitcoinTransactionVertices = bitcoinAddressIndexed.map{case (k,v) => (v,k)}
//			// crearte edes
//
//			val bitcoinTransactionEdges = joinedTransactions.map(joinTuple=>Edge(joinTuple._2._1._1,joinTuple._2._2._1,"input") )
//			// create a default Bitcoin address in case we have transactions that point no-where
//			val missingBitcoinAddress = ("missing")
//			// create graph
//			val graph = Graph(bitcoinTransactionVertices, bitcoinTransactionEdges, missingBitcoinAddress)
//			// calculate input degrees
//
//			val inDegreeInformation = graph.outerJoinVertices(graph.inDegrees)((vid,bitcoinAddress,deg) => (bitcoinAddress,deg.getOrElse(0)))
//		    	// save top 5 bitcoin addresses with the most inputs in output directory
//		inDegreeInformation.vertices.top(5).foreach(println)
//			val saveRdd=sc.parallelize(inDegreeInformation.vertices.top(5)(Ordering.by(_._2._2)))
//			saveRdd.repartition(1).saveAsTextFile(outputFile)
	}

	// extract relevant data
	def extractTransactionData(bitcoinBlock: BitcoinBlock): Array[(String,Array[Byte],Long,Array[Byte], Long,Int,Long)] = {
		// first we need to determine the size of the result set by calculating the total number of inputs multiplied by the outputs of each transaction in the block
		val transactionCount= bitcoinBlock.getTransactions().size()
		var resultSize=0
		for (i<-0 to transactionCount-1) {
			resultSize += bitcoinBlock.getTransactions().get(i).getListOfInputs().size()*bitcoinBlock.getTransactions().get(i).getListOfOutputs().size()
		}

		// then we can create a tuple for each transaction input: Destination Address (which can be found in the output!), Input Transaction Hash, Current Transaction Hash, Current Transaction Output
		// as you can see there is no 1:1 or 1:n mapping from input to output in the Bitcoin blockchain, but n:m (all inputs are assigned to all outputs), cf. https://en.bitcoin.it/wiki/From_address
		val result:Array[(String,Array[Byte],Long,Array[Byte], Long,Int,Long)]=new Array[(String,Array[Byte],Long,Array[Byte],Long,Int,Long)](resultSize)
		var resultCounter: Int = 0
		for (i <- 0 to transactionCount-1) { // for each transaction
			val currentTransaction=bitcoinBlock.getTransactions().get(i)
			val currentTransactionHash=BitcoinUtil.getTransactionHash(currentTransaction)
			for (j <-0 to  currentTransaction.getListOfInputs().size()-1) { // for each input
				val currentTransactionInput=currentTransaction.getListOfInputs().get(j)
				val currentTransactionInputHash=currentTransactionInput.getPrevTransactionHash()
				val currentTransactionInputOutputIndex=currentTransactionInput.getPreviousTxOutIndex()
				for (k <-0 to currentTransaction.getListOfOutputs().size()-1) {
					val currentTransactionOutput=currentTransaction.getListOfOutputs().get(k)
					var currentTransactionOutputIndex=k.toLong
					result(resultCounter)=(BitcoinScriptPatternParser.getPaymentDestination(currentTransactionOutput.getTxOutScript()),currentTransactionInputHash,currentTransactionInputOutputIndex,currentTransactionHash,currentTransactionOutputIndex,bitcoinBlock.getTime(),currentTransactionOutput.getValue().longValue())
					resultCounter+=1
				}
			}

		}
		result;
	}


}


/**
	* Helper class to make byte arrays comparable
	*
	*
	*/
class ByteArray(val bArray: Array[Byte]) extends Serializable {
	override val hashCode = bArray.deep.hashCode
	override def equals(obj:Any) = obj.isInstanceOf[ByteArray] && obj.asInstanceOf[ByteArray].bArray.deep == this.bArray.deep
}

