import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.UnsupportedTagException;
import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;

import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Player {

    /**
     * The MPEG audio bitstream.
     */
    private Bitstream bitstream;
    /**
     * The MPEG audio decoder.
     */
    private Decoder decoder;
    /**
     * The AudioDevice where audio samples are written to.
     */
    private AudioDevice device;

    private PlayerWindow window;

    private boolean isPlaying = false;
    private boolean isPaused = false;
    private boolean previousScrubberState = false;
    private boolean stopPlaying = false;

    private int currentFrame = 0;
    private int count = 0;
    private int songIndex;

    private Song currentSong;
    private ArrayList<Song> songs = new ArrayList<>();
    private ArrayList<String[]> songsData = new ArrayList<>();
    private String [][] playlist = {};

    private int currentSongTime;
    private int totalSongTime;

    private final Lock locker = new ReentrantLock();

    private final ActionListener buttonListenerPlayNow = e -> play();
    private final ActionListener buttonListenerRemove = e -> removeSong();
    private final ActionListener buttonListenerAddSong = e -> addSong();
    private final ActionListener buttonListenerPlayPause = e -> pauseSong();
    private final ActionListener buttonListenerStop = e -> stopSong();
    private final ActionListener buttonListenerNext = e -> nextSong();
    private final ActionListener buttonListenerPrevious = e -> previousSong();
    private final ActionListener buttonListenerShuffle = e -> {};
    private final ActionListener buttonListenerLoop = e -> {};
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
            releasedScrubber();
        }

        @Override
        public void mousePressed(MouseEvent e) {
            pressedScrubber();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
        }
    };

    public Player() {
        EventQueue.invokeLater(() -> window = new PlayerWindow(
                "Mengão player",
                playlist,
                buttonListenerPlayNow,
                buttonListenerRemove,
                buttonListenerAddSong,
                buttonListenerShuffle,
                buttonListenerPrevious,
                buttonListenerPlayPause,
                buttonListenerStop,
                buttonListenerNext,
                buttonListenerLoop,
                scrubberMouseInputAdapter)
        );
    }

    //<editor-fold desc="Essential">

    /**
     * @return False if there are no more frames to play.
     */
    private boolean playNextFrame() throws JavaLayerException {
        // TODO: Is this thread safe?
        if (device != null) {
            Header h = bitstream.readFrame();
            if (h == null) return false;

            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            device.write(output.getBuffer(), 0, output.getBufferLength());
            bitstream.closeFrame();
        }
        return true;
    }

    /**
     * @return False if there are no more frames to skip.
     */
    private boolean skipNextFrame() throws BitstreamException {
        // TODO: Is this thread safe?
        Header h = bitstream.readFrame();
        if (h == null) return false;
        bitstream.closeFrame();
        currentFrame++;
        return true;
    }

    /**
     * Skips bitstream to the target frame if the new frame is higher than the current one.
     *
     * @param newFrame Frame to skip to.
     * @throws BitstreamException Generic Bitstream exception.
     */
    private void skipToFrame(int newFrame) throws BitstreamException {
        // TODO: Is this thread safe?
        if (newFrame > currentFrame) {
            int framesToSkip = newFrame - currentFrame;
            boolean condition = true;
            while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
        }
    }

    public int getSongIndex() {
        String selectedSong = window.getSelectedSong(); // gets the selected song UUID in the user interface

        for (int i = 0; i < songs.size(); i++) { // iterate through the song list to find which song has the same UUID
            if (songs.get(i).getUuid() == selectedSong) {
                return i; // returns its index
            }
        }

        return 0; // returns 0 in case no song is selected
    }

    public void queue()
    {
        try {
            locker.lock();

            String[][] data = new String[this.songsData.size()][7];
            this.playlist = this.songsData.toArray(data);
            window.setQueueList(this.playlist);
        } finally {
            locker.unlock();
        }

    }

    public void songLoop()
    {
        isPlaying= true;
        Thread playingSong = new Thread(()->
        {
            while(true)
            {
                while (!isPaused)
                {
                    try
                    {
                        if (stopPlaying)
                        {
                            isPlaying = false;
                            break;
                        }

                        // updates song time info with the current time and total time, respectively
                        currentSongTime = (int) (count * currentSong.getMsPerFrame());
                        totalSongTime = (int) currentSong.getMsLength();
                        window.setTime(currentSongTime, totalSongTime);

                        if (window.getScrubberValue() < currentSong.getMsLength())
                        {isPlaying = playNextFrame();}
                        else
                        {
                            // verifies if there are any songs left to play in the playlist
                            if (songIndex < songs.size()) {
                                nextSong(); // if so, plays the next one
                            } else {stopSong();} // else, stops playing
                        }
                        count++;
                    }
                    catch (JavaLayerException e) {}
                }

                if(stopPlaying)
                {   // resets the player states
                    stopPlaying = false;
                    isPaused = false;
                    break;
                }
            }
        }
        );
        playingSong.start();
    }

    public void play() {
        songIndex = getSongIndex();
        playSong(songIndex); // gets the selected song index and calls the function that plays the song
    }

    public void playSong(int songIndex)
    {
        currentFrame = 0;
        count = 0;

        if (isPlaying) stopPlaying = true; // interrupts the current song before playing another

        try
        {
            locker.lock();

            isPlaying = true;
            currentSong = songs.get(songIndex);

            // setting the current song info to the window
            window.setPlayingSongInfo(currentSong.getTitle(), currentSong.getAlbum(), currentSong.getArtist());

            // enabling ui buttons according to its conditions
            window.setPlayPauseButtonIcon(isPaused ? 0 : 1);
            window.setEnabledScrubber(isPlaying);
            window.setEnabledPlayPauseButton(isPlaying);
            window.setEnabledStopButton(isPlaying);
            window.setEnabledNextButton(isPlaying && songIndex < songs.size() - 1);
            window.setEnabledPreviousButton(isPlaying && songIndex != 0);

            isPaused = false;

            try
            {
                // getting audio service
                device = FactoryRegistry.systemRegistry().createAudioDevice();
                device.open(decoder = new Decoder());
                bitstream = new Bitstream(currentSong.getBufferedInputStream());
                songLoop(); // starting the playing loop
            }
            catch (JavaLayerException | FileNotFoundException e) {}
        }
        finally {
            locker.unlock();
        }
    }

    public void removeSong() {
        try
        {
            locker.lock();

            int songToRemoveIndex = getSongIndex(); // gets selected song index

            if(songToRemoveIndex == songIndex) {stopSong();} // if the selected song is playing right now, stops it
            if(songToRemoveIndex < songIndex) {songIndex--;} // else, updates the playing song index

            // removes the song from songs and songs data lists
            songs.remove(songToRemoveIndex);
            songsData.remove(songToRemoveIndex);

            queue(); // re-queue the songs list
        }
        finally {
            locker.unlock();
        }
    }

    public void addSong()
    {
        Song newSong;

        try {
            locker.lock();

            newSong = window.openFileChooser();
            songs.add(newSong);

            String [] songData = newSong.getDisplayInfo();
            songsData.add(songData);

            queue();
        }
        catch (IOException | BitstreamException | UnsupportedTagException | InvalidDataException exception) {}

        finally {
            locker.unlock();
        }
    }

    public void pauseSong() {
        try {
            locker.lock();
            isPlaying = isPaused;
            isPaused = !isPaused; // inverts both variables values

            window.setPlayPauseButtonIcon(isPaused ? 0 : 1); // sets the play button active
        }
        finally {
            locker.unlock();
        }

    }

    public void stopSong() {
        try {
            locker.lock();
            isPlaying = false;
            stopPlaying = true;
            count = 0; // resets all code play states

            window.resetMiniPlayer(); // resets the player states
        }
        finally {
            locker.unlock();
        }
    }

    public void nextSong() {
        try {
            locker.lock();

            if (songIndex < songs.size() - 1) { //verifies if the current song is the last in the list
                playSong(songIndex + 1);
                songIndex++; // if not, plays the song immediately after to the current and increments one to songIndex
            }
        } finally {
            locker.lock();
        }
    }

    public void previousSong() {
        try {
            locker.lock();

            if (songIndex != 0) { //verifies if the current song is the first in the list
                playSong(songIndex - 1);
                songIndex--; // if not, plays the song immediately before to the current and decrements one to songIndex
            }
        } finally {
            locker.lock();
        }
    }

    public void pressedScrubber() {
        try {
            locker.lock();

            // saves the previous state of isPaused and pauses the song
            previousScrubberState = isPaused;
            isPaused = true;
        } finally {
            locker.unlock();
        }
    }

    public void releasedScrubber() {
        try {
            locker.lock();

            try {
                // resets the song frame
                currentFrame = 0;
                device = FactoryRegistry.systemRegistry().createAudioDevice();
                device.open(decoder = new Decoder());
                bitstream = new Bitstream(currentSong.getBufferedInputStream());
            }
            catch (JavaLayerException | FileNotFoundException e) {
            }

            // calculates the new time with the scrubber position
            int newTime = (int) (window.getScrubberValue() / currentSong.getMsPerFrame());
            count = newTime;

            window.setTime((int) (count * currentSong.getMsPerFrame()), totalSongTime); // updates song time

            try {
                skipToFrame(newTime); // skips to the right frame

            } catch (BitstreamException e){
                System.out.println(e);
            }

            if (isPlaying) {isPaused = previousScrubberState;} // get previous playing state
        } finally {
            locker.unlock();
        }
    }

    //</editor-fold>
}
