package com.bugcord.coreplugins.polls.creation

import com.bugcord.entities.RNMessage
import com.discord.api.message.poll.MessagePoll

@Suppress("unused")
internal class PollCreatePayload(private val poll: MessagePoll) : RNMessage()
