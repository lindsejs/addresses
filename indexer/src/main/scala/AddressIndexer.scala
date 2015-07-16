package lv.addresses.indexer

import scala.collection.JavaConverters._
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import scala.language.postfixOps

trait AddressIndexer { this: AddressFinder =>

  case class AddrObj(code: Int, typ: Int, name: String, superCode: Int, zipCode: String,
      words: Vector[String]) {
    def foldLeft[A](z: A)(o: (A, AddrObj) => A): A =
      addressMap.get(superCode).map(ao => ao.foldLeft(o(z, this))(o)).getOrElse(o(z, this))
    def foldRight[A](z: A)(o: (A, AddrObj) => A): A =
      addressMap.get(superCode).map(ao => ao.foldRight(z)(o)).map(o(_, this)).getOrElse(o(z, this))
    def depth = foldLeft(0)((d, _) => d + 1)
  }

  val typeOrderMap = Map[Int, Int](
    104 -> 1, //pilsēta
    113 -> 2, //novads
    105 -> 3, //pagasts
    106 -> 4, //ciems
    107 -> 5, //iela
    108 -> 6, //nekustama lieta (māja)
    109 -> 7 //dzivoklis
    )

  protected var _index: scala.collection.mutable.HashMap[String, Array[Long]] = null

  def searchCodes(str: String, limit: Int = 20, types: Set[Int] = null) =
    (searchParams(str)
      .map(_index.getOrElse(_, Array[Long]()))
      .sortWith((a, b) => a.size < b.size) match {
        case Array() => Array[Long]()
        case Array(a) =>
          (if (types == null) a
          else a.filter(c => types.contains(addressMap((c & 0x00000000FFFFFFFFL).asInstanceOf[Int]).typ)))
          .take (if (limit == -1) a.length else limit)
        case a: Array[Array[Long]] => a.tail.foldLeft(scala.collection.mutable.ArrayBuffer[Long]() ++ a.head)(
          (r, a) => {
            val nr = scala.collection.mutable.ArrayBuffer[Long]()
            var (i, j) = (0, 0)
            while (i < r.length && j < a.length) {
              var rv = r(i)
              var av = a(j)
              if (rv == av) {
                i += 1
                j += 1
                if (types == null ||
                    types.contains(addressMap((rv & 0x00000000FFFFFFFFL).asInstanceOf[Int]).typ))
                  nr.append(rv)
              } else if (rv < av) i += 1 else j += 1
            }
            nr
          }).toArray match { case a if limit == -1 => a case a => a.take(limit) }
      }).map(_ & 0x00000000FFFFFFFFL).map(_.toInt)

  def searchParams(str: String) = wordStat(str)
    .map(t => if (t._2 == 1) t._1 else t._2 + "*" + t._1).toArray

  def index(addressMap: Map[Int, AddrObj]) = {

    println("Starting address indexing...")
    val start = System.currentTimeMillis
    
    println(s"Sorting ${addressMap.size} addresses...")
    val addresses = new Array[(Int, Int, String)](addressMap.size)
    var idx = 0
    addressMap.foreach(a => {
      addresses(idx) = (a._1, a._2.depth * 100 + typeOrderMap(a._2.typ),
          a._2.foldRight(new scala.collection.mutable.StringBuilder())((b, o) =>
            b.append(" ").append(unaccent(o.name))).toString)
      idx += 1
    })
    val sortedAddresses = addresses.sortWith((a1, a2) => a1._2 < a2._2 || (a1._2 == a2._2 &&
        (a1._3.length < a2._3.length || (a1._3.length == a2._3.length && a1._3 < a2._3))))
    
    println("Creating index...")
    idx = 0
    val index = sortedAddresses
      .foldLeft(scala.collection.mutable.HashMap[String, scala.collection.mutable.ArrayBuffer[Long]]())(
        (index, addrTuple) => {
          def ref(idx: Long, code: Long) = (idx << 32) | code
          val o = addressMap(addrTuple._1)
          val r = ref(idx, o.code)
          val words = extractWords(addrTuple._3)
          words foreach (w => {
            if (index contains w) index(w).append(r)
            else index(w) = scala.collection.mutable.ArrayBuffer(r)
          })
          idx += 1
          if (idx % 5000 == 0) println(s"Addresses processed: $idx; word cache size: ${index.size}")
          index
        })
      .map(t => t._1 -> t._2.toArray)

    val refCount = index.foldLeft(0L)((c, t) => c + t._2.size)
    val end = System.currentTimeMillis
    println(s"Address objects processed: $idx; word cache size: ${index.size}; ref count: $refCount, ${end - start}ms")

    this._index = index
  }

  def wordStat(phrase: String) = normalize(phrase)
    .foldLeft(Map[String, Int]())((stat, w) =>
      (0 until w.length)
        .map(w.dropRight(_))
        .foldLeft(stat)((stat, w) => stat + (w -> stat.get(w).map(_ + 1).getOrElse(1))))

  def extractWords(phrase: String) = wordStat(phrase)
    .flatMap(t => List(t._1) ++ (2 to t._2).map(_ + "*" + t._1))

  val accents = "ēūīāšģķļžčņ" zip "euiasgklzcn" toMap

  def unaccent(str: String) = str
    .toLowerCase
    .foldLeft(new scala.collection.mutable.StringBuilder(str.length))(
      (b, c) => b.append(accents.getOrElse(c, c)))
    .toString

  //better performance, whitespaces are eliminated in the same run as unaccent operation
  def normalize(str: String) = str
    .toLowerCase
    .foldLeft(scala.collection.mutable.ArrayBuffer[scala.collection.mutable.StringBuilder]() -> true)(
      (b, c) => if (c.isWhitespace || "-,/.".contains(c)) (b._1, true)
      else {
        if (b._2) b._1.append(new scala.collection.mutable.StringBuilder)
        b._1.last.append(accents.getOrElse(c, c))
        (b._1, false)
      })._1
    .map(_.toString)
    .toArray

}

trait AddressLoader { this: AddressFinder =>

  val files: Map[String, (Array[String]) => AddrObj] =
    Map("AW_CIEMS.CSV" -> conv _,
      "AW_DZIV.CSV" -> conv_dziv _,
      "AW_IELA.CSV" -> conv _,
      "AW_NLIETA.CSV" -> conv_nlt _,
      "AW_NOVADS.CSV" -> conv _,
      "AW_PAGASTS.CSV" -> conv _,
      "AW_PILSETA.CSV" -> conv _,
      "AW_RAJONS.CSV" -> conv _).filter(t => !(blackList contains t._1))

  def conv(line: Array[String]) = AddrObj(line(0).toInt, line(1).toInt, line(2), line(3).toInt, null,
      normalize(line(2)).toVector)
  def conv_nlt(line: Array[String]) = AddrObj(line(0).toInt, line(1).toInt, line(7), line(5).toInt, line(9),
      normalize(line(7)).toVector)
  def conv_dziv(line: Array[String]) = AddrObj(line(0).toInt, line(1).toInt, line(7), line(5).toInt, null,
      normalize(line(7)).toVector)

  def loadAddresses(addressZipFile: String = addressFileName) = {
    println(s"Loading addreses from file $addressZipFile...")
    val start = System.currentTimeMillis
    var currentFile: String = null
    var converter: Array[String] => AddrObj = null
    val f = new java.util.zip.ZipFile(addressZipFile)
    val addressMap = f.entries.asScala
      .filter(files contains _.getName)
      .map(f => { println(s"loading file: $f"); converter = files(f.getName); currentFile = f.getName; f })
      .map(f.getInputStream(_))
      .map(scala.io.Source.fromInputStream(_, "Cp1257"))
      .flatMap(_.getLines.drop(1))
      .filter(l => {
        val r = l.split(";")
        //use only existing addresses - status: EKS
        (if (Set("AW_NLIETA.CSV", "AW_DZIV.CSV") contains currentFile) r(2) else r(7)) == "#EKS#"
      })
      .map(r => converter(r.split(";").map(_.drop(1).dropRight(1))))
      .map(o => o.code -> o)
      .toMap
    f.close
    println(s"${addressMap.size} addresses loaded in ${System.currentTimeMillis - start}ms")
    addressMap
  }

}

trait AddressIndexLoader { this: AddressIndexer =>
  import java.io._
  def save(addressMap: Map[Int, AddrObj],
    index: scala.collection.mutable.Map[String, Array[Long]],
    akFileName: String) = {

    println(s"Saving address index for $akFileName...")
    val start = System.currentTimeMillis
    val idxFile = indexFile(akFileName)
    if (idxFile.exists) sys.error(s"Cannot save address index file. File $idxFile already exists")
    val maxRefArray = index.maxBy(_._2.length)
    val maxRefArrayLength = maxRefArray._2.length
    println(s"Max. reference array length for the word '${maxRefArray._1}': ${maxRefArray._2.length}")
    val os = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(idxFile)))
    try {
      os.writeInt(maxRefArrayLength)
      index.foreach(i => {
        os.writeUTF(i._1)
        os.writeInt(i._2.length)
        i._2 foreach os.writeLong
      })
    } finally os.close

    val addrFile = addressCacheFile(akFileName)
    if (addrFile.exists) sys.error(s"Cannot save address file. File $addrFile already exists")
    val w = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
      new FileOutputStream(addrFile), "UTF-8")))
    try {
      addressMap.foreach(a => {
        import a._2._
        w.println(s"$code;$typ;$name;$superCode;${Option(zipCode).getOrElse("")}")
      })
    } finally w.close
    println(s"Address index saved in ${System.currentTimeMillis - start}ms")
  }
  def load(akFileName: String) = {
    println(s"Loading address index for $akFileName...")
    val start = System.currentTimeMillis
    val idxFile = indexFile(akFileName)
    if (!idxFile.exists) sys.error(s"Index file $idxFile not found")
    val in = new DataInputStream(new BufferedInputStream(new FileInputStream(idxFile)))
    val index = scala.collection.mutable.HashMap[String, Array[Long]]()
    var c = 0
    var a = new Array[Long](in.readInt)
    try {
      while (in.available > 0) {
        val w = in.readUTF
        val l = in.readInt
        if (a.length < l) a = new Array[Long](l)
        //(0 until l) foreach (a(_) = in.readLong) this is slow
        var i = 0
        while (i < l) {
          a(i) = in.readLong
          i += 1
        }
        index += (w -> (a take l))
        c += 1
      }
    } finally in.close
    println(s"Total words loaded: $c")
    val addrFile = addressCacheFile(akFileName)
    if (!addrFile.exists) sys.error(s"Address file $addrFile not found")
    var addressMap = Map[Int, AddrObj]()
    var ac = 0
    scala.io.Source.fromInputStream(new BufferedInputStream(new FileInputStream(addrFile)), "UTF-8")
      .getLines
      .foreach(l => {
        ac += 1
        val a = l.split(";").padTo(5, null)
        val o =
          try AddrObj(a(0).toInt, a(1).toInt, a(2), a(3).toInt, a(4), normalize(a(2)).toVector)
          catch {
            case e: Exception => throw new RuntimeException(s"Error at line $ac: $l", e)
          }
        addressMap += (o.code -> o)
      })
    println(s"Address index loaded (words - $c, addresses - $ac) in ${System.currentTimeMillis - start}ms")
    (addressMap, index)
  }

  def hasIndex(akFileName: String) = addressCacheFile(akFileName).exists && indexFile(akFileName).exists

  private def addressCacheFile(akFileName: String) = cacheFile(akFileName, "addresses")
  private def indexFile(akFileName: String) = cacheFile(akFileName, "index")

  private def cacheFile(akFileName: String, extension: String) = {
    val akFile = new File(akFileName)
    val filePrefix = akFile.getName.split("\\.").head
    new File(akFile.getParent, filePrefix + s".$extension")
  }
}

case class Address(code: Int, address: String, zipCode: String, typ: Int)
case class AddressStruct(
  pilCode: Option[Int] = None, pilName: Option[String] = None,
  novCode: Option[Int] = None, novName: Option[String] = None,
  pagCode: Option[Int] = None, pagName: Option[String] = None,
  cieCode: Option[Int] = None, cieName: Option[String] = None,
  ielCode: Option[Int] = None, ielName: Option[String] = None,
  nltCode: Option[Int] = None, nltName: Option[String] = None,
  dzvCode: Option[Int] = None, dzvName: Option[String] = None)

trait AddressFinder extends AddressIndexer
with AddressIndexLoader with AddressLoader with AddressIndexerConfig {

  private[this] var _addressMap: Map[Int, AddrObj] = null

  def addressMap = _addressMap

  def init: Unit = {
    if (ready) return
    if (addressFileName == null) println("Address file not set")
    else {
      if (hasIndex(addressFileName)) loadIndex else {
        _addressMap = loadAddresses()
        index(addressMap)
        saveIndex
      }
    }
  }

  def ready = _index != null && addressMap != null

  def checkIndex = if (_index == null || addressMap == null)
    sys.error("""
           Address index not found. Check whether 'VZD.ak-file' property is set and points to existing file.
           If method is called from console make sure that method index(<address register zip file>) or loadIndex is called first""")

  @deprecated("use search method with improved ranking", "")
  def searchOld(str: String, limit: Int = 20, types: Set[Int] = null) = {
    checkIndex
    //deprecated
    //sort(str, searchCodes(str, limit * 3) map address) take limit

    //choose first addreses where seqMatchCount equals word count
    val codes = searchCodes(str, -1, types)
    val words = normalize(str)
    (if (words.length < 2) codes take limit
    else {
      var (goodCount, otherCount, i) = (0, 0, 0)
      val size = Math.min(codes.length, limit)
      val result = new Array[Int](size)
      while (goodCount < size && i < codes.length) {
        if (seqMatchCount(words, codes(i)) == words.length) {
          result(goodCount) = codes(i)
          goodCount += 1
        } else {
          //not good matches add from the bottom of array
          otherCount += 1
          if (goodCount + otherCount <= size) result(size - otherCount) = codes(i)
        }
        i += 1
      }
      //revert others
      0 until (size - goodCount)/2 foreach { x =>
        val c = result(goodCount + x)
        result(goodCount + x) = result(size - 1 - x)
        result(size - 1 - x) = c
      }
      result
    }) map address
  }
  def search(str: String, limit: Int = 20, types: Set[Int] = null) = {
    checkIndex
    val codes = searchCodes(str, -1, types)
    val words = normalize(str)
    (if (words.length < 2) codes take limit
    else {
      var (perfectRankCount, i) = (0, 0)
      val size = Math.min(codes.length, limit)
      
      val result = new scala.collection.mutable.ArrayBuffer[Long](size)
      while (perfectRankCount < size && i < codes.length) {
        val code = codes(i)
        val r = rank(words, code)
        if (r == 0) perfectRankCount += 1
        val key = r.toLong << 53 | i.toLong << 32 | code
        insertIntoHeap(result, key)
        i += 1
      }
      (if (size < result.size / 2) heap_topx(result, size) else result.sorted.toArray)
        .map(_ & 0x00000000FFFFFFFFL)
        .map(_.toInt)
    }) map address
  }

  def addressStruct(code: Int) = {
    def s(st: AddressStruct, typ: Int, code: Int, name: String) = typ match {
      case 104 => st.copy(pilCode = Option(code), pilName = Option(name))
      case 113 => st.copy(novCode = Option(code), novName = Option(name))
      case 105 => st.copy(pagCode = Option(code), pagName = Option(name))
      case 106 => st.copy(cieCode = Option(code), cieName = Option(name))
      case 107 => st.copy(ielCode = Option(code), ielName = Option(name))
      case 108 => st.copy(nltCode = Option(code), nltName = Option(name))
      case 109 => st.copy(dzvCode = Option(code), dzvName = Option(name))
      case _ => st
    }
    addressMap
    .get(code)
    .map(_.foldLeft(AddressStruct())((st, o) => s(st, o.typ, o.code, o.name)))
    .getOrElse(AddressStruct())
  }

  def addressOption(code: Int) =
    addressMap
      .get(code)
      .map {
        _.foldRight((new scala.collection.mutable.StringBuilder(), null: String, -1))((r, a) =>
          ( //place dash before apartment, otherwise comma
            if (a.typ == 109) r._1.append(" - " + a.name) else r._1.append(", " + a.name),
            if (r._2 == null) a.zipCode else r._2,
            a.typ))
      }.map(r => Address(code, r._1.drop(2).toString, r._2, r._3))
  
  def address(code: Int) = addressOption(code).get
  

  //matching starts from the end of array and from the end of address object chain
  def seqMatchCount(words: Array[String], code: Int) = {
    def count(s: Int, n: Vector[String]) = {
      var i = s
      var j = n.length - 1
      while (i >= 0 && j >= 0) {
        if (n(j).startsWith(words(i))) i -= 1
        j -= 1
      }
      i
    }
    def run(s: Int, code: Int): Int =
      if (s < 0) s else addressMap
        .get(code)
        .map(o => run(count(s, o.words), o.superCode))
        .getOrElse(s)
    words.length - (run(words.length - 1, code) + 1)
  }
  /**Integer of which last 10 bits are significant. 
   * Of them 5 high order bits denote sequential word match count, 5 low bits denote exact word match count.
   * 0 is highest ranking meaning all words sequentially have exact match */
  def rank(words: Array[String], code: Int) = {
    def count(s: Int, n: Vector[String]) = {
      var seqCount: Int = s >> 16 toShort
      var exactCount: Int = s & 0x0000FFFF toShort 
      var j = n.length - 1
      while (seqCount >= 0 && j >= 0) {
        if (n(j).startsWith(words(seqCount))) {
          if (n(j).length == words(seqCount).length) exactCount -= 1
          seqCount -= 1
        } 
        j -= 1
      }
      seqCount.toShort.toInt << 16 | exactCount.toShort
    }
    def run(s: Int, code: Int): Int =
      if (s < 0) s else addressMap
        .get(code)
        .map(o => run(count(s, o.words), o.superCode))
        .getOrElse(s)
    val i = words.length - 1
    val r = run(i << 16 | i, code)
    val a = (r >> 16).toByte + 1
    val b = (r & 0x0000FFFF).toByte + 1
    a << 5 | b
  }

  def saveIndex = {
    checkIndex
    save(addressMap, _index, addressFileName)
  }

  def loadIndex = {
    val r = load(addressFileName)
    _addressMap = r._1
    _index = r._2
  }
  
  def insertIntoHeap(h: scala.collection.mutable.ArrayBuffer[Long], el: Long) {
    var i = h.size
    var j = 0
    h += el
    do {
      j = (i - 1) / 2
      if (h(i) < h(j)) {
        val x = h(i)
        h(i) = h(j)
        h(j) = x
      }
      i = j
    } while (j > 0)
  }
  def heap_topx(h: scala.collection.mutable.ArrayBuffer[Long], x: Int) = {
    def swap(i: Int, j: Int) = {
      val x = h(j)
      h(j) = h(i)
      h(i) = x
    }
    //sort heap
    var (i, c, n) = (0, x, h.size)
    while (n > 0 && c > 0) {
      n -= 1
      c -= 1
      swap(0, n)
      i = 0
      while (i < n) {
        var j = i * 2 + 1
        var k = j + 1
        if (j < n)
          if (k < n && h(k) < h(j))
            if (h(k) < h(i)) {
              swap(i, k)
              i = k
            } else i = n
          else if (h(j) < h(i)) {
            swap(i, j)
            i = j          
        } else i = n
        else i = n
      }
    }
    //take top x elements
    val na = new Array[Long](x)
    i = 0
    val s = h.size - 1
    while (i < x) {
      na(i) = h(s - i)
      i += 1
    }
    na
  }
      
  def heapsortx(a: scala.collection.mutable.ArrayBuffer[Long], x: Int) = {
    def swap(i: Int, j: Int) = {
      val x = a(j)
      a(j) = a(i)
      a(i) = x
    }
    //build heap
    var (i, n) = (0, a.length)
    while (i < n) {
      var j = i
      do {
        var k = j
        j = (j - 1) / 2
        if (a(j) > a(k)) swap(j, k)
      } while (j > 0)
      i += 1
    }
    //get x smallest elements
    heap_topx(a, x)
  }
  
  //for debugging purposes
  def t(block: => Any) = {
    val t1 = System.currentTimeMillis
    block
    System.currentTimeMillis - t1
  }

}

trait AddressIndexerConfig {
  def addressFileName: String
  def blackList: Set[String]
}