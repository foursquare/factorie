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

package cc.factorie.app.nlp.coref
import cc.factorie._
import cc.factorie.app.nlp._
import scala.collection.mutable.{ArrayBuffer,ListBuffer}

/** A trait for entities (and mentions, sub-entities and super-entities) in a coreference problem. */
trait Entity {
  def string: String
  def superEntityRef: EntityRef
  def superEntity: Entity = { val ref = superEntityRef; if (superEntityRef eq null) null else superEntityRef.dst }
  def setSuperEntity(e:Entity)(implicit d:DiffList): Unit = superEntityRef.set(e)
  def subEntities: Iterable[Entity]
  // Next two methods should only be called in EntityRef
  def _addSubEntity(e:Entity)(implicit d:DiffList): Unit
  def _removeSubEntity(e:Entity)(implicit d:DiffList): Unit
  /** Recursively descend sub-entities to return only the Mentions */
  def mentions: Seq[Mention] = {
    var result = new ListBuffer[Mention]
    for (entity <- subEntities) {
      entity match {
        case mention:Mention => result += mention
        case _ => {}
      }
      result ++= entity.mentions
    }
    result
  }
  def mentionsOfClass[A<:Mention](cls:Class[A]): Seq[A] = {
    var result = new ListBuffer[A]
    for (entity <- subEntities) {
      if (cls.isAssignableFrom(entity.getClass))
        result += entity.asInstanceOf[A]
      result ++= entity.mentionsOfClass[A](cls)
    }
    result
  }
  def mentionsOfClass[A<:Mention](implicit m:Manifest[A]): Seq[A] = mentionsOfClass[A](m.erasure.asInstanceOf[Class[A]])
}

trait Mention extends Entity {
  // Just aliases for nicer names
  def entity: Entity = superEntity
  def entityRef: EntityRef = superEntityRef
  def setEntity(e:Entity)(implicit d:DiffList): Unit = setSuperEntity(e)
}

class PairwiseBoolean(val m1:PairwiseMention, val m2:PairwiseMention, b:Boolean) extends LabelVariable(b) with BooleanVar {
  def other(m:PairwiseMention): Option[PairwiseMention] = if(m == m1) Some(m2) else if (m == m2) Some(m1) else None
}
trait PairwiseMention extends TokenSpanMention {
  val edges = new ArrayBuffer[PairwiseBoolean]
  var _head: Token = null
  def headToken: Token = _head
}
abstract class PairwiseTemplate extends Template2[PairwiseBoolean,PairwiseEdge] with Statistics2[BooleanValue,CorefAffinity] {
  def statistics(v:Values): Stat = {
    val mention1 = v._2._1
    val mention2 = v._2._2
    val coref: Boolean = v._1.booleanValue
    new Stat(null, null)
  }
}
abstract class TransitivityTemplate extends Template3[PairwiseBoolean,PairwiseBoolean,PairwiseBoolean] with Statistics1[BooleanValue] {
  //def unroll1(p:PairwiseBoolean) = for (m1 <- p.edge.src.edges; m2 <- p.edge.dst.edges; if (m1)
}

// In the world view with sub-entities and super-entities, a mention is a kind of entity with no sub-entities
trait TokenSpanMention extends TokenSpan with Mention {
  def superEntityRef: EntityRef = attr[EntityRef]
  //def setSuperEntity(e:Entity)(implicit d:DiffList): Unit = entityRef.setDst(e)
  private def _subEntities: SubEntities = attr[SubEntities]
  private def _ensuredSubEntities: SubEntities = attr.getOrElseUpdate[SubEntities](new SubEntities)
  def subEntities: Iterable[Entity] = { val se = _subEntities; if (se eq null) Nil else se }
  def _addSubEntity(e:Entity)(implicit d:DiffList): Unit = _ensuredSubEntities.add(e)
  def _removeSubEntity(e:Entity)(implicit d:DiffList): Unit = _ensuredSubEntities.remove(e)
  def sentence = this(0).sentence
}

class SubEntities extends SetVariable[Entity]


class EntityVariable(var initialCanonicalString:String) extends SetVariable[Entity] with Entity with Attr {
  //def entityRef: EntityRef = attr[EntityRef]
  val canonical = new StringVariable(initialCanonicalString)
  def canonicalString: String = canonical.value
  def string = canonicalString
  //def mentions: scala.collection.Set[Entity] = value
  val superEntityRef = new EntityRef(this, null)
  def subEntities = value
  def _addSubEntity(e:Entity)(implicit d:DiffList): Unit = add(e)
  def _removeSubEntity(e:Entity)(implicit d:DiffList): Unit = remove(e)

  //def closestPreviousMention(position:Int): Entity = mentions.filter(_.start < position).toSeq.sortBy(- _.start).head
  override def toString = "Entity("+string+":"+mentions.toSeq.size+")"
}

class EntityRef(theSrc:Entity, initialDst:Entity) extends ArrowVariable(theSrc, initialDst) {
  if (dst ne null) dst._addSubEntity(src)(null)
  override def set(e:Entity)(implicit d:DiffList): Unit = {
    if (e ne dst) {
      if (dst ne null) dst._removeSubEntity(src)
      e._addSubEntity(src)
      super.set(src, e)
    }
  }
  final def mention = src
  final def entity = dst
}

// Everything below here will be moved to a cc.factorie.example soon.

object CorefAffinityDimensionDomain extends EnumDomain {
  val Bias, ExactMatch, SuffixMatch, EntityContainsMention, EditDistance2, EditDistance4, NormalizedEditDistance9, NormalizedEditDistance5, Singleton = Value
}
object CorefAffinityDomain extends CategoricalVectorDomain[String] {
  override lazy val dimensionDomain = CorefAffinityDimensionDomain
}
class CorefAffinity extends BinaryFeatureVectorVariable[String] {
  def domain = CorefAffinityDomain
}

class EntityMentionModel extends TemplateModel(
  new Template1[EntityRef] with DotStatistics1[CorefAffinity#Value] {
    override def statisticsDomains = List(CorefAffinityDimensionDomain)
    //println("*** EntityMentionModel index="+CorefAffinityDomain.dimensionDomain.index("ExactMatch"))
    weights(CorefAffinityDimensionDomain.Bias) = -1
    weights(CorefAffinityDimensionDomain.ExactMatch) = 10
    weights(CorefAffinityDimensionDomain.SuffixMatch) = 2
    weights(CorefAffinityDimensionDomain.EntityContainsMention) = 3
    weights(CorefAffinityDimensionDomain.EditDistance2) = 4
    weights(CorefAffinityDimensionDomain.NormalizedEditDistance9) = -10
    weights(CorefAffinityDimensionDomain.NormalizedEditDistance5) = -2
    weights(CorefAffinityDimensionDomain.Singleton) = -1
    def statistics(values:Values) = {
      val mention: Entity = values._1._1
      val entity: Entity = values._1._2
      val affinity = new CorefAffinity
      if (mention.string == entity.string) affinity += CorefAffinityDimensionDomain.ExactMatch
      if (mention.string.takeRight(4) == entity.string.takeRight(4)) affinity += CorefAffinityDimensionDomain.SuffixMatch
      if (entity.string.contains(mention.string)) affinity += CorefAffinityDimensionDomain.EntityContainsMention
      val editDistance = entity.string.editDistance(mention.string)
      val normalizedEditDistance = editDistance / entity.string.length
      if (editDistance <= 2) affinity += CorefAffinityDimensionDomain.EditDistance2
      if (editDistance <= 4) affinity += CorefAffinityDimensionDomain.EditDistance4
      if (normalizedEditDistance > .5) affinity += CorefAffinityDimensionDomain.NormalizedEditDistance5
      if (normalizedEditDistance > .9) affinity += CorefAffinityDimensionDomain.NormalizedEditDistance9
      if (entity.subEntities.size == 1) affinity += CorefAffinityDimensionDomain.Singleton
      val result = Stat(affinity.value)
      val str = result.toString
      //println("### EntityMentionModel Stat="+str)
      result
    }
  }
)

class PairwiseModel extends TemplateModel(
  new Template1[EntityRef] with DotStatistics1[CorefAffinity#Value] {
    override def statisticsDomains = List(CorefAffinityDimensionDomain)
    //println("*** PairwiseModel index="+CorefAffinityDomain.dimensionDomain.index("ExactMatch"))
    weights(CorefAffinityDimensionDomain.Bias) = -1
    weights(CorefAffinityDimensionDomain.ExactMatch) = 10
    weights(CorefAffinityDimensionDomain.SuffixMatch) = 2
    weights(CorefAffinityDimensionDomain.EntityContainsMention) = 3
    weights(CorefAffinityDimensionDomain.EditDistance2) = 4
    weights(CorefAffinityDimensionDomain.NormalizedEditDistance9) = -10
    weights(CorefAffinityDimensionDomain.NormalizedEditDistance5) = -2
    weights(CorefAffinityDimensionDomain.Singleton) = -1
    def statistics(values:Values) = {
      val mention: Entity = values._1._1
      val entity: Entity = values._1._2
      val affinity = new CorefAffinity
      if (mention.string == entity.string) affinity += CorefAffinityDimensionDomain.ExactMatch
      if (mention.string.takeRight(4) == entity.string.takeRight(4)) affinity += CorefAffinityDimensionDomain.SuffixMatch
      if (entity.string.contains(mention.string)) affinity += CorefAffinityDimensionDomain.EntityContainsMention
      val editDistance = entity.string.editDistance(mention.string)
      val normalizedEditDistance = editDistance / entity.string.length
      if (editDistance <= 2) affinity += CorefAffinityDimensionDomain.EditDistance2
      if (editDistance <= 4) affinity += CorefAffinityDimensionDomain.EditDistance4
      if (normalizedEditDistance > .5) affinity += CorefAffinityDimensionDomain.NormalizedEditDistance5
      if (normalizedEditDistance > .9) affinity += CorefAffinityDimensionDomain.NormalizedEditDistance9
      if (entity.subEntities.size == 1) affinity += CorefAffinityDimensionDomain.Singleton
      val result = Stat(affinity.value)
      val str = result.toString
      //println("### PairwiseModel Stat="+str)
      result
    }
  }
)

/*class PairwiseModel2 extends TemplateModel(
  new Template4[EntityRef2,EntityRef2,Mention,Mention] with DotStatistics1[DiscreteVectorValue] {
    def unroll1 (er:EntityRef2) = for (other <- er.value.mentions; if (other.entityRef.value == er.value)) yield 
        if (er.mention.hashCode > other.hashCode) Factor(er, other.entityRef, er.mention, other.entityRef.mention)
        else Factor(er, other.entityRef, other.entityRef.mention, er.mention)
    def unroll2 (er:EntityRef2) = Nil // symmetric
    def unroll3 (mention:Mention) = throw new Error
    def unroll4 (mention:Mention) = throw new Error
    def statistics (values:Values) = throw new Error // Stat(new MentionAffinity(values._3, values._4).value)
  }
)*/

object EntityTest {

  def brainDeadMentionExtraction(doc:Document): Unit = {
    for (i <- 0 until doc.length) {
      // Make a mention for simple pronouns
      if (doc(i).string.matches("[Hh]e|[Ss]he|[Ii]t") && doc(i).spans.length == 0) new TokenSpan(doc, i, 1)(null) with TokenSpanMention
      // Make a mention for sequences of capitalized words
      if (doc(i).isCapitalized && doc(i).spans.length == 0) {
        var len = 1
        while (i + len < doc.length && doc(i+len).isCapitalized) len += 1
        new TokenSpan(doc, i, len)(null) with TokenSpanMention
      }
    }
  }

  object spanner extends cc.factorie.app.nlp.ner.SpanNerPredictor(new java.io.File("/Users/mccallum/tmp/spanner.factorie"))
  def nerMentionExtraction(doc:Document): Unit = {
    for (iteration <- 1 to 3; token <- doc) spanner.process(token)
  }

  def corefInit(doc:Document): Unit = {
    //val entities = new ArrayBuffer[Entity]
    // Make each Mention its own Entity
    for (mention <- doc.spansOfClass[TokenSpanMention]) mention.attr += new EntityRef(mention, new EntityVariable("NULL"))
    // Assign each mention to its closest previous non-pronoun mention
    /*for (mention <- doc.orderedSpansOfClass[Mention]) {
      val prevMention = mention.document.spanOfClassPreceeding[Mention](mention.start)
      if (prevMention != null)
        mention.entityRef.setDst(prevMention.entity)(null)
    }*/
  }

  object EntityRefSampler extends SettingsSampler[EntityRef](new EntityMentionModel, null) {
    def settings(entityRef:EntityRef) : SettingIterator = new SettingIterator {
      val mention = entityRef.src.asInstanceOf[TokenSpanMention]
      val changes = new scala.collection.mutable.ArrayBuffer[(DiffList)=>Unit];
      // The "no change" proposal
      changes += {(d:DiffList) => {}}
      // Proposals to make coref with each of the previous mentions
      for (antecedant <- mention.document.spansOfClassPreceeding[TokenSpanMention](mention.start))
        changes += {(d:DiffList) => entityRef.set(antecedant)(d)}
      var i = 0
      def hasNext = i < changes.length
      def next(d:DiffList) = { val d = new DiffList; changes(i).apply(d); i += 1; d }
      def reset = i = 0
    }
    /*override def proposalsHook(proposals:Seq[Proposal]): Unit = {
      println("Proposals")
      for (p <- proposals) {
        println(p)
        println(model.factors(p.diff).map(_.statistics))
      }
      println
      //{ proposals.foreach(p => println(p+"  "+(if (p.modelScore > 0.0) "MM" else ""))); println }
      super.proposalsHook(proposals)
    }
    override def pickProposal(proposals:Seq[Proposal]): Proposal = {
      val result = super.pickProposal(proposals)
      println("EntityRefSampler picked "+result); println()
      result
    }*/

  }

  def coref(doc:Document): Unit = {
    for (mention <- doc.orderedSpansOfClass[TokenSpanMention]) {
      EntityRefSampler.process(mention.entityRef)
    }
  }

  def main(args:Array[String]): Unit = {
    println("Entity running...")
    //val doc = LoadPlainText.fromString("USAToday", docString.takeWhile(_ != '\n'), false)
    val doc = LoadPlainText.fromString("USAToday", docString1.take(800), false)
    //for (token <- doc) println(token.string)
    brainDeadMentionExtraction(doc)
    corefInit(doc)
    coref(doc)
    // Print the results
    for (mention <- doc.orderedSpansOfClass[TokenSpanMention]) {
      println(mention+" => "+mention.entity+"\n")
    }
  }

  val docString1 = """
Laying out a populist argument for his re-election next year, President Obama ventured into the conservative heartland on Tuesday to deliver his most pointed appeal yet for a strong governmental role through tax and regulation to level the economic playing field.
"This country succeeds when everyone gets a fair shot, when everyone does their fair share and when everyone plays by the same rules," Mr. Obama said in an address that sought to tie his economic differences with Republicans into an overarching message.
Infusing his speech with the moralistic language that has emerged in the Occupy protests around the nation, Mr. Obama warned that growing income inequality meant that the United States was undermining its middle class and, "gives lie to the promise that's at the very heart of America:  that this is the place where you can make it if you try."
"This is a make-or-break moment for the middle class, and all those who are fighting to get into the middle class," Mr. Obama told the crowd packed into the gym at Osawatomie High School.
"At stake," he said, "is whether this will be a country where working people can earn enough to raise a family, build a modest savings, own a home, and secure their retirement."
Mr. Obama purposefully chose this hardscrabble town of 4,500 people, about 50 miles south of Kansas City, Kan., where Theodore Roosevelt once laid out the progressive platform he called "the New Nationalism" to put forth his case for a payroll tax cut and his broader arguments against the Republican economic agenda in what his aides hoped would be viewed as a defining speech.
Though it was lacking in specific new policy prescriptions, the hourlong speech, and the days of buildup that preceded it, marked the president's starkest attack on what he described as the "breathtaking greed" that contributed to the economic turmoil still reverberating around the nation. At one point, he noted that the average income of the top 1 percent - adopting the marker that has been the focus of the Occupy movement - has gone up by more than 250 percent, to $1.2 million a year.
The new tack reflected a decision by the White House and the president's campaign aides that - with the economic recovery still lagging and Republicans in Congress continuing to oppose the president's jobs proposals - the best course for Mr. Obama is to try to present himself as the defender of working-class Americans and Republicans as defenders of a small elite.
Republicans, though, portrayed the visit to Osawatomie (pronounced oh-suh-WAHT-ah-mee) as an effort by the president to paper over his failed stewardship of the national economy. Though unemployment levels dropped to 8.6 percent last month, they remain higher than the level at which any president has been re-elected since the Great Depression.
Mitt Romney, one of the contenders for the Republican presidential nomination, dismissed the president's address. "I thought, 'In what way is he like Teddy Roosevelt?' " Mr. Romney said. "Teddy Roosevelt founded the Bull Moose Party. One of those words applies when the president talks about how he's helped the economy."
The trip was Mr. Obama's third out of Washington in as many weeks to press for passage of the payroll tax break, which would reduce the how much employees pay for Social Security to 3.1 percent from the already reduced level of 4.2 percent. Under the Democratic proposal, which Republicans have blocked, the cut that would go to most working Americans would be offset in the budget by a 1. 9 percent surtax on those with modified adjusted gross incomes of more than $1 million. If Congress takes no action, the tax will revert back to 6.2 percent next month.
In Washington, the two parties remained at an impasse in their efforts to write legislation to extend the tax cut, with Senate Republicans rejecting the latest Democratic proposal and House Republicans still writing their own plan.
Though the earlier speeches on the payroll tax took place in swing states, the fact that the president brought the message to one of the most reliably Republican states in the country shows that he and his party are increasingly confident that they have found a message that resonates with voters.
This speech, however, was cast in broad historical terms, with Mr. Obama declaring that that after a century of struggle to build it, the middle class has been steadily eroded, even before the current economic turmoil, by Republican policies intended to reduce the size and scope of government - ranging from tax cuts for the wealthy to deregulation of Wall Street.
"Fewer and fewer of the folks who contributed to the success of our economy actually benefited from that success," he said. "Those at the very top grew wealthier from their incomes and investments than ever before.  But everyone else struggled with costs that were growing and paychecks that weren't - and too many families found themselves racking up more and more debt."
Mr. Obama sought to pre-empt a Republican response that he was engaging in class warfare. "This isn't about class warfare," he said. "This is about the nation's welfare."
The visit was unusual for its setting in a state that he lost decisively despite his own family roots  - his mother was born in Kansas. The vast majority of his visits as president have been to swing states like Pennsylvania that are expected to play an important role in next year's election. But it was here, 101 years ago, that Theodore Roosevelt laid the intellectual framework for his unsuccessful bid for a third term after leaving the Republican party. That speech, which Mr. Obama referred to repeatedly, touched on many of the same themes - often in similar language - like concentration of wealth and the need for government to ensure a level playing field. Central to progress, Mr. Roosevelt said, was the conflict between "the men who possess more than they have earned and the men who have earned more than they possess."
Mr. Obama, to laughter from those familiar with attacks against him, noted: "For this, Roosevelt was called a radical, he was called a socialist, even a communist."
After the speech, one woman in the audience, Debra Harrison said the president put voice to her concerns about this community, which has been eroded by job losses and depopulation.
"We're doing what the middle class has always done in this country," said Ms. Harrison, 51, who works at a nearby bank, shaking her head. "We work hard. We teach our kids to work hard. But it's very hard for us to keep our heads above water these days. And it's even harder for our kids."
    """

  val docString2 = """
Several thousand protesters took to the streets Monday night and accused Prime Minister Vladimir Putin's party of rigging this weekend's parliamentary election in which it won the largest share of the seats.
It was perhaps the biggest opposition rally in years and ended with police detaining about 300 activists. A group of several hundred marched toward the Central Elections Commission near the Kremlin, but were stopped by riot police and taken away in buses.
Estimates of the number of protesters ranged from 5,000 to 10,000. They chanted "Russia without Putin" and accused his United Russia party of stealing votes.
In St. Petersburg, police detained about 120 protesters.
United Russia won about 50 percent of Sunday's vote, a result that opposition politicians and election monitors said was inflated because of ballot-box stuffing and other vote fraud. It was a significant drop from the last election, when the party took 64 percent.
Pragmatically, the loss of seats in the State Duma appears to mean little because two of the three other parties winning seats have been reliable supporters of government legislation.
Nevertheless, it was a substantial symbolic blow to a party that had become virtually indistinguishable from the state itself.
The result has also energized the opposition and poses a humbling challenge to Putin, the country's dominant figure, in his drive to return to the presidency.
Putin, who became prime minister in 2008 because of presidential term limits, will run for a third term in March, and some opposition leaders saw the parliamentary election as a game-changer for what had been presumed to be his easy stroll back to the Kremlin.
More than 400 Communist Party supporters also gathered Monday to express their indignation over the election, which some called the dirtiest in modern Russian history. The Communists finished second with about 20 percent of the vote.
"Even compared to the 2007 elections, violations by the authorities and the government bodies that actually control the work of all election organizations at all levels, from local to central, were so obvious and so brazen," said Yevgeny Dorovin, a member of the party's central committee.
Putin appeared subdued and glum even as he insisted at a Cabinet meeting Monday that the result "gives United Russia the possibility to work calmly and smoothly."
Although the sharp decline for United Russia could lead Putin and the party to try to portray the election as genuinely democratic, the wide reports of violations have undermined that attempt at spin.
Boris Nemtsov, a prominent figure among Russia's beleaguered liberal opposition, declared that the vote spelled the end of Putin's "honeymoon" with the nation and predicted that his rule will soon "collapse like a house of cards."
"He needs to hold an honest presidential election and allow opposition candidates to register for the race, if he doesn't want to be booed from Kamchatka to Kaliningrad," Nemtsov said on Ekho Moskvy radio.
Many Russians have come to despise United Russia, seeing it as the engine of endemic corruption. The balloting showed voters that they have power despite what election monitors called a dishonest count.
"Yesterday, it was proven by these voters that not everything was fixed, that the result really matters," said Tiny Kox of the Council of Europe's Parliamentary Assembly, part of an international election observer mission.
Other analysts suggested the vote was a wake-up call to Putin that he had lost touch with the country. In the early period of his presidency, Putin's appeal came largely from his man-of-the-people image: candid, decisive and without ostentatious tastes.
He seemed to lose some of the common touch, appearing in well-staged but increasingly preposterous heroic photo opportunities - hunting a whale with a crossbow, fishing while bare-chested, and purportedly discovering ancient Greek artifacts while scuba diving. And Russians grew angry at his apparent disregard - and even encouragement - of the country's corruption and massive income gap.
"People want Putin to go back to what he was in his first term - decisive, dynamic, tough on oligarchs and sensitive to the agenda formed by society," said Sergei Markov, a prominent United Russia Duma member.
The vote "was a normal reaction of the population to the worsening social situation," former Kremlin-connected political analyst Gleb Pavlovsky was quoted as saying by the Interfax news agency.
Only seven parties were allowed to field candidates for parliament this year, while the most vocal opposition groups were barred from the race. International monitors said the election administration lacked independence, most media were biased and state authorities interfered unduly at different levels.
"To me, this election was like a game in which only some players are allowed to compete," said Heidi Tagliavini, the head of the observer mission of the Organization for Security and Cooperation in Europe.
Of the 150 polling stations where the counting was observed, "34 were assessed to be very bad," Tagliavini said.
U.S. Secretary of State Hillary Rodham Clinton said Washington has "serious concerns" about the elections.
"Russian voters deserve a full investigation of all credible reports of electoral fraud and manipulation, and we hope in particular that the Russian authorities will take action" on reports that come forward, Clinton said.
Other than the Communist Party, the socialist Just Russia and the Liberal Democratic Party led by mercurial nationalist Vladimir Zhirinovsky are also expected to increase their representation in the Duma; both have generally voted with United Russia, and the Communists pose only token opposition.
Two liberal parties were in the running, but neither got the 7 percent of the national vote needed to win seats. Nemtsov's People's Freedom Party, one of the most prominent liberal parties, was denied participation for alleged violations in the required 45,000 signatures the party had submitted with its registration application.
About 60 percent of Russia's 110 million registered voters cast ballots, down from 64 percent four years ago.
Social media were flooded with messages reporting violations. Many people reported seeing buses deliver groups of people to polling stations, with some of the buses carrying young men who looked like football fans, who often are associated with violent nationalism.
Russia's only independent election monitoring group, Golos, which is funded by U.S. and European grants, has come under heavy official pressure in the past week. Golos' website was incapacitated Sunday by hackers, and its director Lilya Shibanova and her deputy had their cellphone numbers, email and social media accounts hacked.
    """
}
