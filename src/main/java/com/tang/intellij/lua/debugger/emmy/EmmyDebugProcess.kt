/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.debugger.emmy

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.Processor
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.tang.intellij.lua.debugger.LuaDebugProcess
import com.tang.intellij.lua.debugger.LuaDebuggerEditorsProvider
import com.tang.intellij.lua.debugger.LuaExecutionStack
import com.tang.intellij.lua.debugger.LuaSuspendContext
import com.tang.intellij.lua.psi.LuaFileUtil
import gherkin.deps.com.google.gson.Gson

interface IEvalResultHandler {
    fun handleMessage(msg: EvalRsp)
}

class EmmyDebugProcess(session: XDebugSession) : LuaDebugProcess(session), ITransportHandler {
    private val editorsProvider = LuaDebuggerEditorsProvider()
    private val evalHandlers = mutableListOf<IEvalResultHandler>()
    private var transporter: Transporter? = null
    private val configuration = session.runProfile as EmmyDebugConfiguration

    override fun sessionInitialized() {
        super.sessionInitialized()
        val transporter: Transporter = when (configuration.type) {
            EmmyDebugTransportType.PIPE_CLIENT -> PipelineClientTransporter(configuration.pipeName)
            EmmyDebugTransportType.PIPE_SERVER -> PipelineServerTransporter(configuration.pipeName)
            EmmyDebugTransportType.TCP_CLIENT -> SocketClientTransporter(configuration.host, configuration.port)
            EmmyDebugTransportType.TCP_SERVER -> SocketServerTransporter(configuration.host, configuration.port)
        }
        transporter.handler = this
        transporter.logger = this
        this.transporter = transporter
        transporter.start()
    }

    override fun onConnect(suc: Boolean) {
        if (suc) {
            processBreakpoint(Processor { bp ->
                bp.sourcePosition?.let {
                    registerBreakpoint(it, bp)
                }
                true
            })
        }
        else stop()
    }

    override fun onDisconnect() {
        stop()
        session?.stop()
    }

    override fun onReceiveMessage(cmd: MessageCMD, json: String) {
        when (cmd) {
            MessageCMD.BreakNotify -> {
                val data = Gson().fromJson(json, BreakNotify::class.java)
                onBreak(data)
            }
            MessageCMD.EvalRsp -> {
                val rsp = Gson().fromJson(json, EvalRsp::class.java)
                onEvalRsp(rsp)
            }
            else -> {
                println("Unknown message: $cmd")
            }
        }
    }

    override fun registerBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val project = session.project
        val file = sourcePosition.file
        val shortPath: String? = LuaFileUtil.getShortPath(project, file)
        if (shortPath != null) {
            val bps = mutableListOf<BreakPoint>()
            LuaFileUtil.getAllAvailablePathsForMob(shortPath, file).forEach{ path ->
                bps.add(BreakPoint(path, breakpoint.line + 1))
            }
            send(AddBreakPointReq(bps))
        }
    }

    override fun unregisterBreakpoint(sourcePosition: XSourcePosition, breakpoint: XLineBreakpoint<*>) {
        val project = session.project
        val file = sourcePosition.file
        val shortPath: String? = LuaFileUtil.getShortPath(project, file)
        if (shortPath != null) {
            val bps = mutableListOf<BreakPoint>()
            LuaFileUtil.getAllAvailablePathsForMob(shortPath, file).forEach{ path ->
                bps.add(BreakPoint(path, breakpoint.line + 1))
            }
            send(RemoveBreakPointReq(bps))
        }
    }

    override fun startPausing() {
        send(DebugActionMessage(DebugAction.Break))
    }

    private fun onBreak(data: BreakNotify) {
        evalHandlers.clear()
        val frames = data.stacks.map { EmmyDebugStackFrame(it, this) }
        val top = frames.firstOrNull { it.sourcePosition != null }
                ?: frames.firstOrNull { it.data.line > 0 }
                ?: frames.firstOrNull()
        val stack = LuaExecutionStack(frames)
        if (top != null)
            stack.setTopFrame(top)
        val breakpoint = top?.sourcePosition?.let { getBreakpoint(it.file, it.line) }
        if (breakpoint != null) {
            ApplicationManager.getApplication().invokeLater {
                session.breakpointReached(breakpoint, null, LuaSuspendContext(stack))
                session.showExecutionPoint()
            }
        } else {
            ApplicationManager.getApplication().invokeLater {
                val se = session
                if (se is XDebugSessionImpl)
                    se.positionReached(LuaSuspendContext(stack), true)
                else
                    se.positionReached(LuaSuspendContext(stack))
                session.showExecutionPoint()
            }
        }
    }

    private fun onEvalRsp(rsp: EvalRsp) {
        evalHandlers.forEach { it.handleMessage(rsp) }
    }

    override fun run() {
        send(DebugActionMessage(DebugAction.Continue))
    }

    override fun stop() {
        send(DebugActionMessage(DebugAction.Stop))
        send(StopSign())
        transporter?.close()
    }

    override fun startStepOver(context: XSuspendContext?) {
        send(DebugActionMessage(DebugAction.StepOver))
    }

    override fun startStepInto(context: XSuspendContext?) {
        send(DebugActionMessage(DebugAction.StepIn))
    }

    override fun startStepOut(context: XSuspendContext?) {
        send(DebugActionMessage(DebugAction.StepOut))
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider {
        return editorsProvider
    }

    fun addEvalResultHandler(handler: IEvalResultHandler) {
        evalHandlers.add(handler)
    }

    fun removeMessageHandler(handler: IEvalResultHandler) {
        evalHandlers.remove(handler)
    }

    fun send(msg: IMessage) {
        transporter?.send(msg)
    }
}