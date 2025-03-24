package pt.paulinoo.musicbotkt

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.lavalink.youtube.YoutubeAudioSourceManager
import io.github.cdimascio.dotenv.Dotenv
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.cache.CacheFlag
import java.util.concurrent.TimeUnit


fun main() {
    val dotenv = Dotenv.load()
    val spotifyClientId = dotenv["SPOTIFY_CLIENT_ID"] ?: throw IllegalStateException("Spotify client ID not found")
    val spotifyClientSecret =
        dotenv["SPOTIFY_CLIENT_SECRET_ID"] ?: throw IllegalStateException("Spotify secret not found")
    val botToken = dotenv["DISCORD_BOT_TOKEN"] ?: throw IllegalStateException("Bot token not found")
    val adminIds = dotenv["ADMIN_IDS"]?.split(",") ?: throw IllegalStateException("Admin IDs not found")
    val accessTokenSpotify = getSpotifyAccessToken(spotifyClientId, spotifyClientSecret)
    val jda = JDABuilder.createDefault(botToken)
        .enableIntents(
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.MESSAGE_CONTENT,
            GatewayIntent.GUILD_VOICE_STATES
        ) // Enable intents
        .enableCache(CacheFlag.VOICE_STATE) // Cache for voice channel support
        .setActivity(Activity.listening("!help"))
        .addEventListeners(MusicBot(accessTokenSpotify, adminIds))
        .build()

    jda.awaitReady()
    println("Bot is ready")
}

class MusicBot(private val accessTokenSpotify: String, private val adminIds: List<String>) : ListenerAdapter() {

    // private val logger = LoggerFactory.getLogger(MusicBot::class.java)


    private val audioPlayerManager = DefaultAudioPlayerManager().apply {
        configuration.opusEncodingQuality = 10
        configuration.resamplingQuality = AudioConfiguration.ResamplingQuality.HIGH
        configuration.outputFormat = StandardAudioDataFormats.DISCORD_OPUS

        registerSourceManager(YoutubeAudioSourceManager()) // Enables adaptive formats
        registerSourceManager(SoundCloudAudioSourceManager.createDefault())
        enableGcMonitoring()
        frameBufferDuration = 5000
        setItemLoaderThreadPoolSize(4)
    }


    private val playerMap = mutableMapOf<Long, AudioPlayerWrapper>()

    private val voiceChannelWarningSent = mutableSetOf<Long>()

    private var nowPlayingMessageId: Long? = null

    override fun onMessageReceived(event: MessageReceivedEvent) {
        val message = event.message.contentRaw
        val channel = event.channel
        val member = event.member

        when {
            message.startsWith("!admin servers") -> {
                if (isAdmin(event.author)) {
                    listServers(event)
                }
            }

            message.startsWith("!admin usage") -> {
                if (isAdmin(event.author)) {
                    checkUsage(event)
                }
            }

            message.startsWith("!play") -> {

                if (!checkIfUserInVoiceChannel(event)) {
                    return
                }
                val voiceChannel = member?.voiceState?.channel as VoiceChannel
                val searchQuery = message.removePrefix("!play ").trim()
                if (searchQuery.isBlank()) {
                    sendEmbedMessage(channel, "", "Please provide a search query!")
                }
                if (searchQuery.contains("spotify.com")) {
                    try {
                        val trackInfo = getSpotifyTrackInfo(accessTokenSpotify, searchQuery)
                        val trackName = trackInfo.name
                        val artistName = trackInfo.artists[0].name
                        val youtubeSearchQuery = "ytmsearch: $trackName $artistName"
                        loadAndPlay(youtubeSearchQuery, voiceChannel, channel, event)
                    } catch (e: Exception) {
                        sendEmbedMessage(channel, "", "Error fetching Spotify track: ${e.message}")
                    }
                } else if (searchQuery.contains("youtube.com") || searchQuery.contains("youtu.be")) {
                    loadAndPlay(searchQuery, voiceChannel, channel, event)
                } else {
                    val youtubeSearchQuery = "ytmsearch: $searchQuery"
                    loadAndPlay(youtubeSearchQuery, voiceChannel, channel, event)
                }
            }

            message.startsWith("!skipto") -> skipToTrack(event)
            message.startsWith("!skip") -> skipTrack(event)
            message.startsWith("!stop") -> stopTrack(event)
            message.startsWith("!pause") -> pauseTrack(event)
            message.startsWith("!resume") -> resumeTrack(event)
            message.startsWith("!volume") -> setVolume(event)
            message.startsWith("!queue") -> showQueue(event)
            message.startsWith("!progress") -> showProgress(event)
            message.startsWith("!clear") -> clearQueue(event)
            message.startsWith("!swap") -> swapTracks(event)
            message.startsWith("!remove") -> removeTrack(event)
            message.startsWith("!shuffle") -> shuffleQueue(event)
            message.startsWith("!reverse") -> reverseQueue(event)
            message.startsWith("!jump") -> jumpToTrack(event)
            message.startsWith("!config") -> showCurrentAudioConfiguration(event)
        }
    }

    private fun isAdmin(user: User): Boolean {

        return user.id in adminIds
    }

    private fun checkIfUserInVoiceChannel(event: MessageReceivedEvent): Boolean {
        val member = event.member
        val voiceChannel = member?.voiceState?.channel as? VoiceChannel
        val channel = event.channel
        if (voiceChannel == null) {
            if (!voiceChannelWarningSent.contains(event.guild.idLong)) {
                sendEmbedMessage(channel, "", "You need to join a voice channel first!")
                voiceChannelWarningSent.add(event.guild.idLong)
            }
            return false
        } else {
            voiceChannelWarningSent.remove(event.guild.idLong)
            return true
        }
    }

    private fun listServers(event: MessageReceivedEvent) {
        val guilds = event.jda.guilds
        val serverList = guilds.joinToString("\n") { it.name }
        sendEmbedMessage(event.channel, "Servers", serverList)
    }

    private fun checkUsage(event: MessageReceivedEvent) {
        val usageList = playerMap.entries.joinToString("\n") { (guildId, playerWrapper) ->
            val guild = event.jda.getGuildById(guildId)
            val isPlaying = playerWrapper.audioPlayer.playingTrack != null
            "${guild?.name ?: "Unknown"}: ${if (isPlaying) "Playing" else "Idle"}"
        }
        sendEmbedMessage(event.channel, "", usageList.ifEmpty { "Bot is not being used" })
    }


    private fun loadAndPlay(
        query: String,
        voiceChannel: VoiceChannel,
        channel: MessageChannel,
        event: MessageReceivedEvent
    ) {
        val guild = event.guild
        val playerWrapper = playerMap.getOrPut(guild.idLong) {
            val player = audioPlayerManager.createPlayer()
            guild.audioManager.sendingHandler = AudioPlayerSendHandler(player)
            AudioPlayerWrapper(player, guild)
        }

        guild.audioManager.openAudioConnection(voiceChannel)
        guild.audioManager.isSelfDeafened = true

        loadTrack(query) { track ->
            if (track != null) {
                println("Loaded track: ${track.info.title}")
                if (playerWrapper.audioPlayer.playingTrack != null) {
                    playerWrapper.trackQueue.add(track)
                    sendEmbedMessage(channel, "", "Added to queue: ${track.info.title}")
                    updateNowPlayingEmbed(channel, playerWrapper, event.author)
                } else {
                    playAudioTrack(track, playerWrapper, channel, event.author)
                }
            } else {
                println("Failed to find track for query: $query")
                sendEmbedMessage(channel, "", "Could not find a track for '$query'")
            }
        }
    }

    private fun playAudioTrack(
        track: AudioTrack,
        playerWrapper: AudioPlayerWrapper,
        channel: MessageChannel,
        requester: User
    ) {
        playerWrapper.audioPlayer.playTrack(track)
        println("Playing track: ${track.info.title}")
        updateNowPlayingEmbed(channel, playerWrapper, requester)
    }

    private fun updateNowPlayingEmbed(channel: MessageChannel, playerWrapper: AudioPlayerWrapper, requester: User) {
        val isPaused = playerWrapper.audioPlayer.isPaused
        val embed = buildTrackEmbed(playerWrapper, requester, isPaused)
        if (nowPlayingMessageId != null) {
            channel.retrieveMessageById(nowPlayingMessageId!!).queue { message ->
                message.editMessageEmbeds(embed).queue()
            }
        } else {
            channel.sendMessageEmbeds(embed).queue { message ->
                nowPlayingMessageId = message.idLong
            }
        }
    }


    private fun buildTrackEmbed(playerWrapper: AudioPlayerWrapper, requester: User, isPaused: Boolean): MessageEmbed {
        val thumbnailUrl =
            "https://img.youtube.com/vi/${playerWrapper.audioPlayer.playingTrack.identifier}/maxresdefault.jpg"
        val nextTrack = playerWrapper.trackQueue.peek()
        val nextTrackInfo =
            nextTrack?.let { "[${it.info.author} - ${it.info.title}](https://www.youtube.com/watch?v=${it.identifier})" }
                ?: "None"
        val currentVolume = playerWrapper.audioPlayer.volume
        val queueSize = playerWrapper.trackQueue.size
        val totalQueueDuration = playerWrapper.trackQueue.sumOf { it.info.length }
        val status = if (isPaused) "Paused" else "Now Playing"

        val embedBuilder = EmbedBuilder()
            .setTitle(status)
            .setDescription("▶️ [${playerWrapper.audioPlayer.playingTrack.info.author} - ${playerWrapper.audioPlayer.playingTrack.info.title}](https://www.youtube.com/watch?v=${playerWrapper.audioPlayer.playingTrack.info.identifier})")
            .addField("Duration", formatDuration(playerWrapper.audioPlayer.playingTrack.duration), true)
            .addField("Requested by", requester.asMention, true)
            .addField("Volume", "$currentVolume%", true)
            .setThumbnail(thumbnailUrl)
            .setColor(0x111135) // Darker blue to match the image

        if (queueSize > 0) {
            embedBuilder
                .addField("Songs in Queue", queueSize.toString(), true)
                .addField("Total Queue Duration", formatDuration(totalQueueDuration), true)
                .addField("Next Track", nextTrackInfo, false)
        }

        return embedBuilder.build()
    }


    private fun loadTrack(query: String, callback: (AudioTrack?) -> Unit) {
        println("Loading track: $query")
        audioPlayerManager.loadItemOrdered(this, query, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                println("Track loaded: ${track.info.title}")
                callback(track)
            }

            override fun playlistLoaded(playlist: AudioPlaylist) {
                val selectedTrack = playlist.selectedTrack ?: playlist.tracks.firstOrNull()
                println("Playlist found, playing: ${selectedTrack?.info?.title ?: "No track found"}")
                callback(selectedTrack)
            }

            override fun noMatches() {
                println("No matches found for: $query")
                callback(null)
            }

            override fun loadFailed(exception: FriendlyException) {
                println("Track loading failed: ${exception.message}")
                exception.printStackTrace()
                callback(null)
            }
        })
    }


    private fun formatDuration(duration: Long): String {
        val minutes = duration / 60000
        val seconds = (duration % 60000) / 1000
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun skipTrack(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val audioPlayer = playerWrapper.audioPlayer
            if (playerWrapper.trackQueue.isNotEmpty()) {
                val nextTrack = playerWrapper.trackQueue.remove()
                playAudioTrack(nextTrack, playerWrapper, event.channel, event.author)
            } else {
                audioPlayer.stopTrack()
                sendEmbedMessage(event.channel, "", "Track skipped!")
                nowPlayingMessageId = null
            }
        }
    }

    override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
        val guild = event.guild
        val selfMember = guild.selfMember
        val voiceChannel = selfMember.voiceState?.channel

        if (voiceChannel != null && voiceChannel.members.size == 1) {
            val playerWrapper = playerMap[guild.idLong]
            if (playerWrapper != null) {
                val audioPlayer = playerWrapper.audioPlayer
                audioPlayer.stopTrack()
                guild.audioManager.closeAudioConnection()
                playerWrapper.trackQueue.clear()
                val defaultChannel = guild.defaultChannel as? MessageChannel
                if (defaultChannel != null) {
                    sendEmbedMessage(defaultChannel, "", "The voice channel is empty, leaving the channel.")
                }
                nowPlayingMessageId = null
            }
        }
    }

    private fun stopTrack(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val audioPlayer = playerWrapper.audioPlayer
            audioPlayer.stopTrack()
            event.guild.audioManager.closeAudioConnection()
            sendEmbedMessage(event.channel, "", "Playback stopped!")
            nowPlayingMessageId = null
        }
    }

    private fun pauseTrack(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val audioPlayer = playerWrapper.audioPlayer
            if (audioPlayer.isPaused) {
                audioPlayer.isPaused = false
                sendEmbedMessage(event.channel, "", "Playback resumed!")
            } else {
                audioPlayer.isPaused = true
                sendEmbedMessage(event.channel, "", "Playback paused!")
            }
            updateNowPlayingEmbed(event.channel, playerWrapper, event.author)
        }
    }

    private fun resumeTrack(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val audioPlayer = playerWrapper.audioPlayer
            if (audioPlayer.isPaused) {
                audioPlayer.isPaused = false
                sendEmbedMessage(event.channel, "", "Playback resumed!")
                updateNowPlayingEmbed(event.channel, playerWrapper, event.author)
            } else {
                sendEmbedMessage(event.channel, "", "Playback is not paused!")
            }
        }
    }

    private fun setVolume(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val audioPlayer = playerWrapper.audioPlayer
            val message = event.message.contentRaw
            val volumeString = message.removePrefix("!volume").trim()

            if (volumeString.isEmpty()) {
                val currentVolume = audioPlayer.volume
                sendEmbedMessage(event.channel, "", "The current volume is $currentVolume%")
            } else {
                val newVolume = volumeString.toIntOrNull()
                if (newVolume != null && newVolume in 0..200) {
                    audioPlayer.volume = newVolume
                    sendEmbedMessage(event.channel, "", "Volume set to $newVolume%")
                    updateNowPlayingEmbed(event.channel, playerWrapper, event.author)
                } else {
                    sendEmbedMessage(event.channel, "", "Please provide a valid volume level between 0 and 200.")
                }
            }
        }
    }

    private fun getCurrentAudioConfiguration(): String {
        val config = audioPlayerManager.configuration
        val outputFormat = config.outputFormat
        val opusEncodingQuality = config.opusEncodingQuality
        val resamplingQuality = config.resamplingQuality

        return """
        Current Audio Configuration:
        - Output Format: ${outputFormat.codecName()}
        - Opus Encoding Quality: $opusEncodingQuality
        - Resampling Quality: ${resamplingQuality.name}
    """.trimIndent()
    }

    private fun showCurrentAudioConfiguration(event: MessageReceivedEvent) {
        val configInfo = getCurrentAudioConfiguration()
        sendEmbedMessage(event.channel, "", configInfo)
    }

    private fun showQueue(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper == null) {
            val embed = EmbedBuilder()
                .setTitle("Queue")
                .setDescription("The queue is empty!")
                .setColor(0xFF0000)
                .build()
            event.channel.sendMessageEmbeds(embed).queue { message ->
                message.delete().queueAfter(30, TimeUnit.SECONDS)
            }
            return
        }

        val audioPlayer = playerWrapper.audioPlayer
        val track = audioPlayer.playingTrack
        val queue = playerWrapper.trackQueue

        val embedBuilder = EmbedBuilder().setTitle("Queue").setColor(0xFF0000)

        if (track != null) {
            embedBuilder.addField("Now Playing", "**${track.info.title}**", false)
        }

        if (queue.isNotEmpty()) {
            val queueDescription =
                queue.mapIndexed { index, audioTrack -> "${index + 1}. ${audioTrack.info.title}" }.joinToString("\n")
            embedBuilder.addField("Up Next", queueDescription, false)
        } else if (track == null) {
            embedBuilder.setDescription("The queue is empty!")
        }

        val embed = embedBuilder.build()
        event.channel.sendMessageEmbeds(embed).queue { message ->
            message.delete().queueAfter(30, TimeUnit.SECONDS)
        }
    }

    private fun showProgress(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val audioPlayer = playerWrapper.audioPlayer
            val currentTrack = audioPlayer.playingTrack
            if (currentTrack != null) {
                val currentPosition = currentTrack.position
                val duration = currentTrack.duration
                val progressBar = buildProgressBar(currentPosition, duration)
                val embed = EmbedBuilder()
                    .setDescription(progressBar)
                    .addField("Current Position", formatDuration(currentPosition), true)
                    .addField("Duration", formatDuration(duration), true)
                    .setColor(0x111135)
                    .build()
                event.channel.sendMessageEmbeds(embed).queue {
                    it.delete().queueAfter(30, TimeUnit.SECONDS)
                }
            } else {
                sendEmbedMessage(event.channel, "", "No track is currently playing.")
            }
        }
    }

    private fun buildProgressBar(currentPosition: Long, duration: Long): String {
        val progressBarLength = 20
        val progress = (currentPosition.toDouble() / duration * progressBarLength).toInt()
        val progressBar = StringBuilder()
        for (i in 0 until progressBarLength) {
            if (i < progress) {
                progressBar.append("█")
            } else {
                progressBar.append("░")
            }
        }
        return progressBar.toString()
    }

    private fun clearQueue(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            playerWrapper.trackQueue.clear()
            sendEmbedMessage(event.channel, "", "The music queue has been cleared.")
        }
    }

    private fun swapTracks(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val message = event.message.contentRaw
            val args = message.split(" ")
            if (args.size != 3) {
                sendEmbedMessage(event.channel, "Error", "Please provide two valid queue positions to swap.")
                return
            }

            val pos1 = args[1].toIntOrNull()
            val pos2 = args[2].toIntOrNull()

            if (pos1 == null || pos2 == null || pos1 < 1 || pos2 < 1 || pos1 > playerWrapper.trackQueue.size || pos2 > playerWrapper.trackQueue.size) {
                sendEmbedMessage(event.channel, "Error", "Please provide valid queue positions to swap.")
                return
            }

            val queueList = playerWrapper.trackQueue.toList()
            val track1 = queueList[pos1 - 1]
            val track2 = queueList[pos2 - 1]

            playerWrapper.trackQueue.clear()
            queueList.forEachIndexed { index, track ->
                when (index) {
                    pos1 - 1 -> playerWrapper.trackQueue.add(track2)
                    pos2 - 1 -> playerWrapper.trackQueue.add(track1)
                    else -> playerWrapper.trackQueue.add(track)
                }
            }

            sendEmbedMessage(event.channel, "", "Swapped positions $pos1 and $pos2 in the queue.")
        }
    }

    private fun removeTrack(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val message = event.message.contentRaw
            val args = message.split(" ")
            if (args.size != 2) {
                sendEmbedMessage(event.channel, "", "Please provide a valid queue position to remove.")
                return
            }

            val pos = args[1].toIntOrNull()
            if (pos == null || pos < 1 || pos > playerWrapper.trackQueue.size) {
                sendEmbedMessage(event.channel, "", "Please provide a valid queue position to remove.")
                return
            }

            val queueList = playerWrapper.trackQueue.toList()
            val removedTrack = queueList[pos - 1]

            playerWrapper.trackQueue.clear()
            queueList.forEachIndexed { index, track ->
                if (index != pos - 1) {
                    playerWrapper.trackQueue.add(track)
                }
            }

            sendEmbedMessage(event.channel, "", "Removed track: ${removedTrack.info.title}")
        }
    }

    private fun shuffleQueue(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val queueList = playerWrapper.trackQueue.toList()
            playerWrapper.trackQueue.clear()
            queueList.shuffled().forEach { playerWrapper.trackQueue.add(it) }
            sendEmbedMessage(event.channel, "", "The music queue has been shuffled.")
        }
    }

    private fun skipToTrack(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val message = event.message.contentRaw
            val args = message.split(" ")
            if (args.size != 2) {
                sendEmbedMessage(event.channel, "", "Please provide a valid queue position to skip to.")
                return
            }

            val pos = args[1].toIntOrNull()
            if (pos == null || pos < 1 || pos > playerWrapper.trackQueue.size) {
                sendEmbedMessage(event.channel, "", "Please provide a valid queue position to skip to.")
                return
            }

            val queueList = playerWrapper.trackQueue.toList()
            val targetTrack = queueList[pos - 1]

            playerWrapper.trackQueue.clear()
            queueList.drop(pos).forEach { playerWrapper.trackQueue.add(it) }

            playAudioTrack(targetTrack, playerWrapper, event.channel, event.author)
            sendEmbedMessage(event.channel, "", "Now playing: ${targetTrack.info.title}")
        }
    }

    private fun reverseQueue(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val queueList = playerWrapper.trackQueue.toList()
            playerWrapper.trackQueue.clear()
            queueList.reversed().forEach { playerWrapper.trackQueue.add(it) }
            sendEmbedMessage(event.channel, "", "The music queue has been reversed.")
        }
    }

    private fun jumpToTrack(event: MessageReceivedEvent) {
        if (!checkIfUserInVoiceChannel(event)) {
            return
        }
        val playerWrapper = playerMap[event.guild.idLong]
        if (playerWrapper != null) {
            val message = event.message.contentRaw
            val args = message.split(" ")
            if (args.size != 2) {
                sendEmbedMessage(event.channel, "", "Please provide a valid queue position to jump to.")
                return
            }

            val pos = args[1].toIntOrNull()
            if (pos == null || pos < 1 || pos > playerWrapper.trackQueue.size) {
                sendEmbedMessage(event.channel, "", "Please provide a valid queue position to jump to.")
                return
            }

            val queueList = playerWrapper.trackQueue.toList()
            val targetTrack = queueList[pos - 1]

            playerWrapper.trackQueue.clear()
            queueList.subList(0, pos - 1).forEach { playerWrapper.trackQueue.add(it) }
            queueList.drop(pos).forEach { playerWrapper.trackQueue.add(it) }

            playAudioTrack(targetTrack, playerWrapper, event.channel, event.author)
            sendEmbedMessage(event.channel, "", "Now playing: ${targetTrack.info.title}")
        }
    }

    private fun sendEmbedMessage(channel: MessageChannel, title: String, description: String, color: Int = 0xFF0000) {
        val embed = EmbedBuilder()
            .setDescription(description)
            .setColor(color)

        if (title.isNotBlank()) {
            embed.setTitle(title)
        }
        channel.sendMessageEmbeds(embed.build()).queue { message ->
            message.delete().queueAfter(30, TimeUnit.SECONDS)
        }
    }
}

