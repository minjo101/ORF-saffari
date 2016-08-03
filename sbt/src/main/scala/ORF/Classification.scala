package ORF

/** ORF.Classification: Online Random Forest Classification version
 *
 */
object Classification {
  import ORF.Tree
  private val Rand = new scala.util.Random

  case class Param(numClasses: Int, minSamples: Int, minGain: Double, 
                   gamma: Double = 0, numTests: Int = 10, lam: Double=1,
                   metric: String = "entropy") {
    assert(lam <= 10, "Current implementation only supports lam <= 10. lam=1 is suitable for most bootstrapping cases.")
  }

  case class ORTree (param: Param, xRange: Vector[(Double,Double)]) { // for classification
    private var _age = 0
    def age = _age
    val lam = param.lam
    val numTests = if (param.numTests == 0) scala.math.sqrt(xRange.size).toInt else param.numTests
    val numClasses = param.numClasses
    val minSamples = param.minSamples
    val minGain = param.minGain
    val dimX = xRange.size
    private val _oobe = (Array.fill(numClasses)(0), Array.fill(numClasses)(0)) // (# correct, # Total)
    def oobe = { (_oobe._1 zip _oobe._2) map {z => if (z._2 == 0) 0 else 1 - z._1 / z._2.toDouble} }.sum / numClasses

    private var _tree = Tree( Info() ) // Online Tree
    def tree = _tree
    def reset = { 
      _tree = Tree( Info() )
      _age = 0
      for (d <- 0 until numClasses) {
        _oobe._1(d) = 0
        _oobe._2(d) = 0
      }
    }

    private def findLeaf(x: Vector[Double], tree: Tree[Info]): Tree[Info] = {
      if (tree.isLeaf) tree else {
        val (dim,loc) = (tree.elem.splitDim, tree.elem.splitLoc)
        if ( x(dim) > loc ) findLeaf(x, tree.right) else findLeaf(x, tree.left)
      }
    }

    def predict(x: Vector[Double]) = findLeaf(x,tree).elem.pred
    def density(x: Vector[Double]) = findLeaf(x,tree).elem.dens
    private def poisson(lam: Double) = {
      val l = scala.math.exp(-lam)
      def loop(k: Int, p: Double): Int = if (p > l) loop(k+1, p * Rand.nextDouble) else k - 1
      loop(0,1)
    }
    
    def update(x: Vector[Double], y: Int) = { // Updates _tree
      val k = poisson(lam)
      if (k > 0) {
        for (u <- 1 to k) {
          _age = _age + 1
          val j = findLeaf(x,_tree)
          j.elem.update(x,y)
          if (j.elem.numSamplesSeen > minSamples) {
            val g = gains(j.elem)
            if ( g.exists(_ > minGain) ) {
              val bestTest = g.zip(j.elem.tests).maxBy(_._1)._2
              // create Left, Right children
              j.left = Tree( Info() )
              j.right = Tree( Info() )
              j.elem.splitDim = bestTest.dim
              j.elem.splitLoc = bestTest.loc
              j.left.elem.c = bestTest.cLeft
              j.right.elem.c = bestTest.cRight
              j.elem.reset
            }
          }
        }
      } else { // k > 0
        // estimate OOBE: Used for Temporal Knowledge Weighting
        val pred = predict(x)
        _oobe._2(y) += 1
        if (pred == y) _oobe._1(y) += 1
      }
    }

    private def loss(c: Array[Int], metric: String = param.metric) = {
      val n = c.sum.toDouble + numClasses
      (c map { x => 
        val p = x / n
        if (metric == "gini") p * (1-p) else -p * scala.math.log(p)
      }).sum
    }

    private def gains(info: Info) = {
      val tests = info.tests
      tests map { test =>
        val cL = test.cLeft
        val nL = cL.sum + numClasses
        val cR = test.cRight
        val nR = cR.sum + numClasses
        val n = (nL + nR).toDouble
        val g = loss(info.c) - (nL/n) * loss(cL) - (nR/n) * loss(cR)
        if (g < 0) 0 else g
      }
    }

    case class Info(var splitDim: Int = -1, var splitLoc: Double = 0.0) {
      private var _numSamplesSeen = 0
      def numSamplesSeen = _numSamplesSeen
      var c = Array.fill(numClasses)(1)
      def numSamples = c.sum

      case class Test(dim: Int, loc: Double, cLeft: Array[Int], cRight: Array[Int])

      private var _tests = {
        def runif(rng: (Double,Double)) = Rand.nextDouble * (rng._2-rng._1) + rng._1
        def gentest = {
          val dim = Rand.nextInt(dimX)
          val loc = runif(xRange(dim))
          val cLeft = Array.fill(numClasses)(1)
          val cRight = Array.fill(numClasses)(1)
          Test(dim,loc,cLeft,cRight)
        }
        Array.range(0, numTests) map {s => gentest}
      }
      def tests = _tests

      // ToDo: ??? Gini importance: I = Gini - Gini_splitLeft - Gini_splitRight
      def reset = {
        c = Array()
        _tests = Array()
      }

      def update(x: Vector[Double], y: Int) = {
        c(y) += 1
        _numSamplesSeen = _numSamplesSeen + 1
        for (test <- tests) {
          val dim = test.dim
          val loc = test.loc
          if (x(dim) < loc) {
            test.cLeft(y) += 1
          } else {
            test.cRight(y) += 1
          }
        }
      }
      
      def pred = c.zipWithIndex.maxBy(_._1)._2
      def dens = c.map { cc => cc / (numSamples.toDouble + numClasses) }

      override def toString = if (splitDim == -1) pred.toString else "X" + (splitDim+1) + " < " + (splitLoc * 100).round / 100.0
    } // end of case class Info
  } // end of case class ORT

  case class ORForest(param: Param, rng: Vector[(Double,Double)], numTrees: Int = 100, par: Boolean = false) {
    val gamma = param.gamma
    val lam = param.lam
    private var _forest = {
      val f = Vector.range(1,numTrees) map { i => 
        val tree = ORTree(param,rng)
        tree
      }
      if (par) f.par else f
    }
    def forest = _forest
    def predict(x: Vector[Double]) = {
      val preds = forest.map(tree => tree.predict(x)) 
      val predList = preds.groupBy(identity).toList
      predList.maxBy(_._2.size)._1
      /* Alternatively:
      val dens = forest.map(tree => tree.density(x))
      val inds = Vector.range(0,dens.head.size)
      val densMean = inds.map( i => dens.map(d => d(i)).sum / dens.size )
      val out = densMean.zipWithIndex.maxBy(_._1)._2
      out
      */
    }
    def update(x: Vector[Double], y: Int) = {
      _forest.foreach( _.update(x,y) )
      if (gamma > 0) { // Algorithm 2: Temporal Knowledge Weighting
        val oldTrees = forest.filter( t => t.age > 1 / gamma)
        if (oldTrees.size > 0) {
          val t = oldTrees( Rand.nextInt(oldTrees.size) )
          if (t.oobe > Rand.nextDouble) t.reset
        }
      }
    }
    def confusion(xs: Vector[Vector[Double]], ys: Vector[Int]) = {
      assert(xs.size == ys.size, "Error: xs and ys need to have same length")
      val numClasses = param.numClasses
      val preds = xs.map(x => predict(x))
      val conf = Array.fill(numClasses)( Array.fill(numClasses)(0) )
      for ( (y,pred) <- ys zip preds) conf(y)(pred) += 1
      conf
    }
    def printConfusion(conf: Array[Array[Int]]) = {
      println("Confusion Matrix:")
      print("y\\pred\t")
      (0 until param.numClasses).foreach( i => print(i + "\t") )
      println("\n")
      var r = 0
      conf.foreach{ row => 
        print(r + "\t")
        r = r + 1
        row.foreach(c => print(c + "\t"))
        println("\n")
      }
    }
    def predAccuracy(xs: Vector[Vector[Double]], ys: Vector[Int]) = {
      assert(xs.size == ys.size, "Error: xs and ys need to have same length")
      val pt = (xs zip ys) map {z => predict(z._1) == z._2}
      pt.map(predEqualTruth => if (predEqualTruth) 1 else 0).sum / pt.size.toDouble
    }
    def meanTreeSize = forest.map{ot => ot.tree.size}.sum / forest.size.toDouble
    def meanNumLeaves = forest.map{ot => ot.tree.numLeaves}.sum / forest.size.toDouble
    def meanMaxDepth = forest.map{ot => ot.tree.maxDepth}.sum / forest.size.toDouble
    private def sd(xs: Vector[Int]) = {
      val n = xs.size.toDouble
      val mean = xs.sum / n
      scala.math.sqrt( xs.map(x => (x-mean) * (x-mean) ).sum / (n-1) )
    }

    def leaveOneOutCV(xs: Vector[Vector[Double]], ys: Vector[Int], par: Boolean = false) = { // for convenience
      assert(xs.size == ys.size, "Error: xs and ys need to have same length")
      val n = ys.size
      val numClasses = param.numClasses
      val inds = Vector.range(0,n)
      val conf = Array.fill(numClasses)( Array.fill(numClasses)(0) )
      for (i <- inds) {
        val orf = ORForest(param,rng,par=par)
        val indsShuf = Rand.shuffle(0 to n-1) // important
        val trainInds = indsShuf.filter(_!=i)
        trainInds.foreach{ i => orf.update(xs(i),ys(i).toInt) }
        val pred = orf.predict(xs(i))
        val curr = conf(ys(i))(pred)
        conf(ys(i))(pred) = curr + 1
      }
      conf
    }
  }
}