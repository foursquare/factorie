/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */


package cc.factorie.tutorial
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, ListBuffer}
import scala.util.matching.Regex
import scala.io.Source
import java.io.File
import cc.factorie._
import cc.factorie.directed._
import cc.factorie.app.strings.Stopwords
import cc.factorie.app.strings.alphaSegmenter
import cc.factorie.directed._
import cc.factorie.variable._

object LDA2 {
  
  val numTopics = 10
  implicit val model = DirectedModel()
  object ZDomain extends DiscreteDomain(numTopics)
  object ZSeqDomain extends DiscreteSeqDomain { def elementDomain = ZDomain }
  class Zs(len:Int) extends DiscreteSeqVariable(len) { def domain = ZSeqDomain }
  object WordSeqDomain extends CategoricalSeqDomain[String]
  val WordDomain = WordSeqDomain.elementDomain
  class Words(strings:Seq[String]) extends CategoricalSeqVariable(strings) {
    def domain = WordSeqDomain
    def zs = model.parentFactor(this).asInstanceOf[PlatedCategoricalMixture.Factor]._3
  }
  class Document(val file:String, val theta:ProportionsVar, strings:Seq[String]) extends Words(strings)
  val beta = MassesVariable.growableUniform(WordDomain, 0.1)
  val alphas = MassesVariable.dense(numTopics, 0.1)

  def main(args: Array[String]): Unit = {
    implicit val random = new scala.util.Random(0)
    val directories = if (args.length > 0) args.toList else List("12", "11", "10", "09", "08").take(1).map("/Users/mccallum/research/data/text/nipstxt/nips"+_)
    val phis = Mixture(numTopics)(ProportionsVariable.growableDense(WordDomain) ~ Dirichlet(beta))
    val documents = new ArrayBuffer[Document]
    for (directory <- directories) {
      // println("Reading files from directory " + directory)
      for (file <- new File(directory).listFiles; if file.isFile) {
        // print("."); Console.flush
        val theta = ProportionsVariable.dense(numTopics) ~ Dirichlet(alphas)
        val tokens = alphaSegmenter(file).map(_.toLowerCase).filter(!Stopwords.contains(_)).toSeq
        val zs = new Zs(tokens.length) :~ PlatedDiscrete(theta)
        documents += new Document(file.toString, theta, tokens) ~ PlatedCategoricalMixture(phis, zs)
      }
      // println()
    }

    val collapse = new ArrayBuffer[Var]
    collapse += phis
    collapse ++= documents.map(_.theta)
    val sampler = new CollapsedGibbsSampler(collapse, model)
    //println("Initialization:"); phis.foreach(t => println("Topic " + phis.indexOf(t) + "  " + t.top(10).map(dp => WordDomain.getCategory(dp.index)).mkString(" ")))

    val startTime = System.currentTimeMillis
    for (i <- 1 to 20) {
      for (doc <- documents) sampler.process(doc.zs)
      if (i % 5 == 0) {
        // println("Iteration " + i)
        // Turned off hyperparameter optimization
        //DirichletMomentMatching.estimate(alphaMean, alphaPrecision)
        //println("alpha = " + alphaMean.map(_ * alphaPrecision.doubleValue).mkString(" "))
        // phis.foreach(t => println("Topic " + phis.indexOf(t) + "  " + t.value.top(10).map(dp => WordDomain.category(dp.index)).mkString(" ")+"  "+t.value.massTotal.toInt))
        // println("Total words "+phis.map(_.value.massTotal).sum.toInt)
        // println
      }
    }
    //phis.foreach(t => {println("\nTopic "+phis.indexOf(t)); t.top(20).foreach(x => println("%-16s %f".format(x.value,x.pr)))})
    // println("Finished in " + ((System.currentTimeMillis - startTime) / 1000.0) + " seconds")
  }
}
