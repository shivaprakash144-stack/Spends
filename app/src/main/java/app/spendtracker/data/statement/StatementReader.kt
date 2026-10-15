package app.spendtracker.data.statement

import android.content.Context
import android.net.Uri
import app.spendtracker.domain.statement.ParsedStatement
import app.spendtracker.domain.statement.StatementParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface StatementReadResult {
    data class Ok(val statement: ParsedStatement) : StatementReadResult
    data object NeedsPassword : StatementReadResult
    data object WrongPassword : StatementReadResult
    data class Error(val message: String) : StatementReadResult
}

/** Opens a statement PDF the user picked (Storage Access Framework, no storage permission needed). */
@Singleton
class StatementReader @Inject constructor(@ApplicationContext private val context: Context) {

    @Volatile private var initialised = false

    suspend fun read(uri: Uri, password: String?): StatementReadResult = withContext(Dispatchers.IO) {
        if (!initialised) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialised = true
        }
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return@withContext StatementReadResult.Error("Could not open the file")
            val text = stream.use { input ->
                PDDocument.load(input, password ?: "").use { doc ->
                    PDFTextStripper().apply { setSortByPosition(true) }.getText(doc)
                }
            }
            val parsed = StatementParser.parse(text)
            if (parsed.rows.isEmpty()) {
                StatementReadResult.Error("No transactions found. This statement layout isn't supported yet. Scanned (image) PDFs can't be read.")
            } else {
                StatementReadResult.Ok(parsed)
            }
        } catch (e: InvalidPasswordException) {
            if (password.isNullOrEmpty()) StatementReadResult.NeedsPassword else StatementReadResult.WrongPassword
        } catch (e: Exception) {
            StatementReadResult.Error("Could not read this PDF (${e.javaClass.simpleName}).")
        } catch (e: OutOfMemoryError) {
            StatementReadResult.Error("This PDF is too large to read on the phone.")
        }
    }
}
