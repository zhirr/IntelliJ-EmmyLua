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

import gherkin.deps.com.google.gson.Gson

enum class MessageCMD {
    Unknown,
    InitReq,
    InitRsp,

    AddBreakPointReq,
    AddBreakPointRsp,

    RemoveBreakPointReq,
    RemoveBreakPointRsp,

    ActionReq,
    ActionRsp,

    EvalReq,
    EvalRsp,

    // lua -> ide
    BreakNotify,
}

interface IMessage {
    val cmd: Int
    fun toJSON(): String
}

open class Message(cmdName: MessageCMD) : IMessage {
    override val cmd = cmdName.ordinal

    override fun toJSON(): String {
        return Gson().toJson(this)
    }

    companion object {
        private var seqCount = 0

        fun makeSeq(): Int {
            return seqCount++
        }
    }
}

enum class DebugAction {
    Break,
    Continue,
    StepOver,
    StepIn,
    StepOut,
    Stop,
}

class DebugActionMessage(actionName: DebugAction) : Message(MessageCMD.ActionReq) {
    val action = actionName.ordinal
}

class VariableValue(val name: String, val value: String, val type: String, val children: List<VariableValue>?)

class Stack(
        val file: String,
        val line: Int,
        val functionName: String,
        val level: Int,
        val localVariables: List<VariableValue>,
        val upvalueVariables: List<VariableValue>
)

class BreakNotify(val stacks: List<Stack>)

class EvalReq(val expr: String, val stackLevel: Int, val depth: Int = 1) : Message(MessageCMD.EvalReq) {
    val seq = makeSeq()
}

class EvalRsp(val seq: Int, val success: Boolean, val error: String?, val value: VariableValue?)

class BreakPoint(val file: String, val line: Int)

class AddBreakPointReq(val breakPoints: List<BreakPoint>) : Message(MessageCMD.AddBreakPointReq)

class RemoveBreakPointReq(val breakPoints: List<BreakPoint>) : Message(MessageCMD.RemoveBreakPointReq)