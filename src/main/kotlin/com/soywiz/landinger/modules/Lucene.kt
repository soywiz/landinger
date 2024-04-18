package com.soywiz.landinger.modules

/*
import com.soywiz.klock.measureTime
import com.soywiz.landinger.Entries
import org.apache.lucene.analysis.standard.*
import org.apache.lucene.document.*
import org.apache.lucene.index.*
import org.apache.lucene.queryparser.classic.*
import org.apache.lucene.search.*
import org.apache.lucene.store.*

class LuceneIndex {
    val analyzer = StandardAnalyzer()
    val directory = ByteBuffersDirectory()
    val config = IndexWriterConfig(analyzer)

    data class DocumentInfo(val id: String, val title: String, val content: String)

    //val directory = MMapDirectory()
    fun addDocuments(vararg documents: DocumentInfo) {
        val writer = IndexWriter(directory, config)
        for (doc in documents) {
            val document = Document()
            document.add(TextField("id", doc.id, Field.Store.YES)) // @TODO: Do not index
            document.add(TextField("title", doc.title, Field.Store.YES))
            document.add(TextField("content", doc.content, Field.Store.YES))
            writer.addDocument(document)
        }
        writer.close()
    }

    fun search(queryString: String) {
        val reader: IndexReader = DirectoryReader.open(directory)
        val searcher = IndexSearcher(reader)
        val parser = QueryParser("content", analyzer)
        val query: Query? = parser.parse(queryString)
        val results = searcher.search(query, 5)
        println("Hits for $queryString -->" + results.totalHits)
        for (doc in results.scoreDocs) {
            val docId = reader.document(doc.doc).get("id")
            println(" - $doc : $docId")
        }
    }
}

class MyLuceneIndex(val entries: Entries) {
    val luceneIndex: LuceneIndex by lazy {
        LuceneIndex().also { luceneIndex ->
            val indexTime = measureTime {
                luceneIndex.addDocuments(
                    *entries.entries.entries.map {
                        LuceneIndex.DocumentInfo(
                            it.permalink,
                            it.title,
                            it.bodyHtml
                        )
                    }.toTypedArray()
                )

            }
            println("Lucene indexed in $indexTime...")
        }
    }
}
*/
