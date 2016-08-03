import org.scalatest.FunSuite

class TestSuite extends FunSuite {
  import ORF.Classification._
  import ORF.Tools.dataRange
  import Timer.time

  test("Drawing / testing trees") {
    import ORF.Tree
    val x = Tree(1,Tree(202),Tree(303))
    x.draw
    x.left.draw
    println
    assert(!x.isLeaf && x.left.isLeaf)
  }

  test("max depth") {
    import ORF.Tree
    val t = Tree(1)
    val t2 = Tree(1,Tree(2),Tree(3))
    val t3 = Tree(1,Tree(4,t2,t),Tree(5,Tree(6),t2))
    val t4 = t3.copy()
    t4.right = Tree(5)
    assert(t.maxDepth == 1 && t2.maxDepth == 2 && t3.maxDepth == 4 && t4.maxDepth == 4)
  }

  if (true) test("ORT") {
    val iris = scala.io.Source.fromFile("src/test/resources/iris.csv").getLines.map(x=>x.split(",").toVector.map(_.toDouble)).toVector
    val n = iris.size
    val k = iris(0).size - 1
    val y = iris.map( yi => {yi(k) - 1}.toInt )
    val X = iris.map(x => x.take(k))
    val param = Param(numClasses = y.toSet.size, minSamples = 5, 
      minGain = .1, numTests = 10, gamma = 0)

    val orf = ORForest(param,dataRange(X))
    //assert(orf.forest(0) != orf.forest(1))

    val inds = scala.util.Random.shuffle(0 to n-1) // important that order is shuffled

    val (testInds, trainInds) = inds.partition( _ % 5 == 0)
    trainInds.foreach{ i => orf.update(X(i),y(i).toInt) }
    orf.forest(0).tree.draw
    orf.forest(1).tree.draw
    val xtest = testInds.map(X(_)).toVector
    val ytest = testInds.map(y(_)).toVector
    val conf = orf.confusion(xtest,ytest)
    print(Console.YELLOW)
    orf.printConfusion(conf)
    println(orf.predAccuracy(xtest,ytest) + Console.RESET)
    println("mean tree size: " + orf.meanTreeSize)
    //orf.forest.foreach( tree => println(tree.oobe) )

    println
    Timer.time {
      val conflooPar= orf.leaveOneOutCV(X,y,par=true)
      println("Parallel Leave One Out CV") // faster!!!
      orf.printConfusion(conflooPar) }
    print(Console.RESET)
  }

  if (false) test("Online Read") {
    val uspsTrain = scala.util.Random.shuffle(
      scala.io.Source.fromFile("src/test/resources/usps/train.csv").
      getLines.map(x=>x.split(" ").toVector.map(_.toDouble)).toVector)
    val uspsTest = scala.io.Source.fromFile("src/test/resources/usps/test.csv").
      getLines.map(x=>x.split(" ").toVector.map(_.toDouble)).toVector

    val n = uspsTrain.size
    val k = uspsTrain(0).size - 1
    val y = uspsTrain.map( _.head.toInt )
    val X = uspsTrain.map( _.tail )
    println("Dim: " + X.size + "," + X(0).size)
    val param = Param(lam = 1, numClasses = y.toSet.size, 
                      minSamples = n/10, minGain = .1, 
                      numTests = 10, gamma = 0, metric="entropy")
    val inds = Vector.range(0,n)
    val orf = ORForest(param,dataRange(X),numTrees=100,par=true)
    val indsten = Vector.range(0,10).flatMap( i => scala.util.Random.shuffle(inds))
    Timer.time {indsten.foreach{ i => 
      orf.update(X(i),y(i).toInt) 
      print("\r"+orf.meanTreeSize.toInt+"              ")
    }}
    //orf.forest.foreach( f => f.tree.draw )

    println("mean tree size: " + orf.meanTreeSize)
    println("mean tree Depth: " + orf.meanMaxDepth)
    println("c: " + orf.forest(0).tree.elem.c.mkString(","))

    val xtest = uspsTest.map(x => x.tail)
    val ytest = uspsTest.map(yi => yi(0).toInt)
    val conf = orf.confusion(xtest,ytest)
    print(Console.YELLOW)
    orf.printConfusion(conf)
    println(orf.predAccuracy(xtest,ytest) + Console.RESET)
  }
}
