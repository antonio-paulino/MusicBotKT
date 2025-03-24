package pt.paulinoo.musicbotkt

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import java.util.*

data class AudioPlayerWrapper(val audioPlayer: AudioPlayer, val guild: Guild, val trackQueue: Queue<AudioTrack> = LinkedList()) {

    private val timers = mutableMapOf<Long, Timer>()

    init {
        audioPlayer.addListener { event ->
            if (event is TrackEndEvent) {
                if (trackQueue.isNotEmpty()) {
                    val nextTrack = trackQueue.poll()
                    audioPlayer.playTrack(nextTrack)
                } else {
                    handleEmptyQueue(guild)
                }
            }
        }
    }

    private fun handleEmptyQueue(guild: Guild) {
        val channel = guild.defaultChannel as? MessageChannel
        if (channel != null) {
            val embed = EmbedBuilder()
                .setDescription("The queue is empty. The bot will leave the voice channel in 5 minutes.")
                .setColor(0xFF0000)
                .build()
            channel.sendMessageEmbeds(embed).queue()

            // Cancel any existing timer for this guild
            timers[guild.idLong]?.cancel()

            // Schedule the bot to leave the voice channel after 5 minutes
            val timer = Timer()
            timer.schedule(object : TimerTask() {
                override fun run() {
                    guild.audioManager.closeAudioConnection()
                    timers.remove(guild.idLong)
                }
            }, 5 * 60 * 1000)
            timers[guild.idLong] = timer
        }
    }


}