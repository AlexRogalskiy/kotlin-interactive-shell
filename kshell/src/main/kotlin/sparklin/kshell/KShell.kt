package sparklin.kshell

import com.intellij.openapi.Disposable
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.utils.PathUtil
import org.jline.reader.LineReaderBuilder
import org.jline.terminal.TerminalBuilder
import sparklin.kshell.configuration.Configuration
import sparklin.kshell.repl.*
import sparklin.kshell.wrappers.ResultWrapper
import java.io.Closeable
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

open class KShell(val disposable: Disposable,
                  val configuration: Configuration,
                  val messageCollector: MessageCollector,
                  val classpath: List<File>,
                  val moduleName: String,
                  val classLoader: ClassLoader) : Closeable {

    private val compilerConfiguration = CompilerConfiguration().apply {
        addJvmClasspathRoots(PathUtil.getJdkClassesRoots(File(System.getProperty("java.home"))))
        addJvmClasspathRoots(classpath)
        put(CommonConfigurationKeys.MODULE_NAME, moduleName)
        put<MessageCollector>(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, messageCollector)
    }

    val baseClassloader = URLClassLoader(compilerConfiguration.jvmClasspathRoots.map { it.toURI().toURL() }
            .toTypedArray(), classLoader)

    private lateinit var compiler: ReplCompiler
    private lateinit var evaluator: ReplEvaluator
    val state = ReplState(ReentrantReadWriteLock())

    val incompleteLines = arrayListOf<String>()
    val term = TerminalBuilder.builder().build()
    val readerBuilder = LineReaderBuilder.builder().terminal(term)
    val reader = readerBuilder.build()
    val commands = mutableListOf<sparklin.kshell.Command>(FakeQuit())
    val eventManager = EventManager()

    var invokeWrapper: InvokeWrapper? = null

    private class FakeQuit: sparklin.kshell.BaseCommand() {
        override val name: String = "quit"
        override val short: String = "q"
        override val description: String = "quit the kshell"
        override fun execute(line: String) {}
    }

//    open fun buildDefaultCompleter() = Completer.DEFAULT_COMPLETER

    fun listCommands(): Iterable<sparklin.kshell.Command> = commands.asIterable()

    fun addClasspathRoots(files: List<File>) = compilerConfiguration.addJvmClasspathRoots(files)

    fun initEngine() {
//        reader.apply {
//            addCompleter(ContextDependentCompleter(commands, incompleteLines::isEmpty, buildDefaultCompleter()))
//        }

        configuration.load()
        configuration.plugins().forEach { it.init(this, configuration) }

        compiler = ReplCompiler(disposable, compilerConfiguration, messageCollector)
        evaluator = ReplEvaluator(classpath, baseClassloader)
    }

    fun doRun() {
        initEngine()

        do {
            val line = reader.readLine(getPrompt())

            if (line == null || isQuitAction(line)) break

            if (incompleteLines.isEmpty() && line.startsWith(":") && !line.startsWith("::")) {
                try {
                    val action = commands.first { it.match(line) }
                    action.execute(line)
                    state.lineIndex.getAndIncrement()
                } catch (_: NoSuchElementException) {
                    println("Unknown command $line")
                } catch (e: Exception) {
                    commandError(e)
                }
            } else {
                if (line.isBlank() && (incompleteLines.isNotEmpty() && incompleteLines.last().isBlank())) {
                    incompleteLines.clear()
                    println("You typed two blank lines. Starting a new command.")
                } else {
                    val source = (incompleteLines + line).joinToString(separator = "\n")
                    val result = eval(source).result
                    when (result) {
                        is Result.Error -> {
                            incompleteLines.clear()
                            handleError(result.error)
                        }
                        is Result.Success -> {
                            incompleteLines.clear()
                            handleSuccess(result.data)
                        }
                        is Result.Incomplete -> {
                            incompleteLines.add(line)
                        }
                    }
                }
            }
        } while (true)

        cleanUp()
    }

    fun registerCommand(command: sparklin.kshell.Command) {
        commands.add(command)
    }

    private fun isQuitAction(line: String): Boolean {
        return incompleteLines.isEmpty() && (line.equals(":quit", ignoreCase = true) || line.equals(":q", ignoreCase = true))
    }

    private fun nextLine(code: String) = CodeLine(state.lineIndex.getAndIncrement(), code)

    fun compile(code: String) = compiler.compile(state, nextLine(code))

    fun eval(source: String): ResultWrapper =
        state.lock.write {
            val compileResult = compile(source)
            ResultWrapper(when (compileResult) {
                is Result.Incomplete -> {
                    Result.Incomplete()
                }
                is Result.Error -> {
                    Result.Error<EvalResult, EvalError>(compileResult.error)
                }
                is Result.Success -> {
                    eventManager.emitEvent(OnCompile(compileResult.data))
                    evaluator.eval(state, compileResult.data, invokeWrapper)
                }
            })
        }

    fun handleError(error: EvalError) {
        println("Message: ${error.message}")
    }

    fun handleSuccess(result: EvalResult) {
        if (result is EvalResult.ValueResult)
            println(result.toString())
    }

    fun getPrompt() =
        if (incompleteLines.isEmpty())
            "kotlin [${state.lineIndex.get()}]> "
        else
            "... "


    open fun commandError(e: Exception) {
        e.printStackTrace()
    }

    override fun close() {
        disposable.dispose()
    }

    open fun cleanUp() {
    }
}

class OnCompile(private val data: CompilationData) : Event<CompilationData> {
    override fun data(): CompilationData = data
}