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

    //<editor-fold desc="Essentials">
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

    //</editor-fold>

    //<editor-fold desc="Setting necessary booleans">
    private boolean isPlaying = false;
    private boolean isPaused = false;
    private boolean stopPlaying = false;

    private boolean statePreviousToScrubberAction = false;

    private boolean shuffling = false;

    private boolean isLooping = false;
    //</editor-fold>

    //<editor-fold desc="Miscellaneous variables and constant">
    private int currentFrame = 0;
    private int count = 0;
    private int songIndex;
    private Song currentSong;
    private int currentSongTime;
    private int totalSongTime;
    private final Lock locker = new ReentrantLock();
    //</editor-fold>


    //<editor-fold desc="Arrays and lists">
    private ArrayList<Song> songs = new ArrayList<>();
    private ArrayList<String[]> songsData = new ArrayList<>();

    private ArrayList<Song> auxShuffleArray = new ArrayList<>();

    private ArrayList<String[]> auxShuffleArrayData = new ArrayList<>();
    private String [][] playlist = {};
    //</editor-fold>

    //<editor-fold desc="Action Listeners">
    private final ActionListener buttonListenerPlayNow = e -> play();
    private final ActionListener buttonListenerRemove = e -> removeSong();
    private final ActionListener buttonListenerAddSong = e -> addSong();
    private final ActionListener buttonListenerPlayPause = e -> pauseSong();
    private final ActionListener buttonListenerStop = e -> stopSong();
    private final ActionListener buttonListenerNext = e -> nextSong();
    private final ActionListener buttonListenerPrevious = e -> previousSong();
    private final ActionListener buttonListenerShuffle = e -> shuffleSongs();
    private final ActionListener buttonListenerLoop = e -> loopSongs();
    //</editor-fold>

    //<editor-fold desc="Mouse Adapter">
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
    //</editor-fold>

    //<editor-fold desc="Player">
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
        // change the state of the buttons to true, because you can choose to loop and to shuffle even before playing any songs
        EventQueue.invokeLater(() -> window.setEnabledShuffleButton(true));
    }
    //</editor-fold>

    //<editor-fold desc="Functions">

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

    public void queue() //
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
                            if (songIndex < songs.size() - 1) {
                                nextSong(); // if so, plays the next one
                            } else if (songIndex == songs.size() - 1) {
                                if (isLooping){ // else, see whether you should loop
                                    songIndex = 0;
                                    playSong(songIndex);
                                }
                                else{stopSong();} // or whether you should just stop

                            } // else, stops playing
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
            // you can skip to the next song even if you're at the end of the list if you have a loop
            window.setEnabledNextButton(isPlaying && (isLooping || songIndex < songs.size() - 1));
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
            Song songToBeRemoved = songs.get(songToRemoveIndex);

            if(songToRemoveIndex == songIndex) {stopSong();} // if the selected song is playing right now, stops it
            if(songToRemoveIndex < songIndex) {songIndex--;} // else, updates the playing song index

            // removes the song from songs and songs data lists
            songs.remove(songToRemoveIndex);
            songsData.remove(songToRemoveIndex);

            int shuffleIndex = auxShuffleArray.indexOf(songToBeRemoved);
            auxShuffleArray.remove(shuffleIndex);
            auxShuffleArrayData.remove(shuffleIndex);

            window.setEnabledLoopButton(!songs.isEmpty());

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
            auxShuffleArray.add(newSong);

            String [] songData = newSong.getDisplayInfo();
            songsData.add(songData);
            auxShuffleArrayData.add(songData);

            window.setEnabledLoopButton(!songs.isEmpty());
            window.setEnabledNextButton(isPlaying && (isLooping || songIndex < songs.size() - 1));

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
            isPaused = false;
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
                songIndex++; // if not, plays the song immediately after to the current and increments one to songIndex
                playSong(songIndex);
            }
            else if (songIndex == songs.size() - 1 && isLooping) {
                songIndex = 0;
                playSong(songIndex);
            }
        } finally {
            locker.unlock();
        }
    }

    public void previousSong() {
        try {
            locker.lock();

            if (songIndex != 0) { //verifies if the current song is the first in the list
                songIndex--;
                playSong(songIndex); // plays the song that is before that in the list
            }
        } finally {
            locker.unlock();
        }
    }

    public void pressedScrubber() {
        try {
            locker.lock();

            // saves the previous state of isPaused and pauses the song
            statePreviousToScrubberAction = isPaused;
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

            isPaused = statePreviousToScrubberAction; // get previous playing state
            isPlaying = !isPaused;

        } finally {
            locker.unlock();
        }
    }

    public void shuffleSongs(){
        try {
            locker.lock();

            // changing the state of the logical variable of shuffling
            shuffling = !shuffling;

            if (isPaused || isPlaying) {
                Song songPlayingNow = songs.get(songIndex);
                String[] songPlayingNowData = songsData.get(songIndex);
                if (shuffling) {
                    // clearing the data that's in the auxiliary array (which might be shuffled)
                    auxShuffleArray.clear();
                    auxShuffleArrayData.clear();

                    // storing the unshuffled data from the original array in case we need to undo the shuffling
                    auxShuffleArray.addAll(songs);
                    auxShuffleArrayData.addAll(songsData);

                    // shuffling the original array with the Fisher-Yates shuffle algorithm
                    for (int i = 0; i < songs.size(); i++) {
                        int randomIndex =  (int) (Math.random() * (songs.size() - i) + i);

                        Song auxShuffleSong = songs.get(randomIndex);
                        songs.set(randomIndex, songs.get(i));
                        songs.set(i, auxShuffleSong);

                        String[] auxShuffleSongData = songsData.get(randomIndex);
                        songsData.set(randomIndex, songsData.get(i));
                        songsData.set(i, auxShuffleSongData);
                    }

                    // putting the song that was playing in the first position
                    int songPlayingNowNewIndex = songs.indexOf(songPlayingNow);
                    songs.set(songPlayingNowNewIndex, songs.get(0));
                    songs.set(0, songPlayingNow);

                    songsData.set(songPlayingNowNewIndex, songsData.get(0));
                    songsData.set(0, songPlayingNowData);

                    songIndex = 0;

                    queue();
                }
                else {
                    //restore the original order:
                    songs.clear();
                    songsData.clear();
                    songs.addAll(auxShuffleArray);
                    songsData.addAll(auxShuffleArrayData);

                    // update the index of the song that is currently playing
                    songIndex = songs.indexOf(songPlayingNow);

                    queue();
                }
            }
            else {
                if (shuffling) {
                    // do the same thing as before, only now you don't need to reassign any songs to the position 0, nor do you need to change songIndex
                    // clearing the data that's in the auxiliary array (which might be shuffled)
                    auxShuffleArray.clear();
                    auxShuffleArrayData.clear();

                    // storing the unshuffled data from the original array in case we need to undo the shuffling
                    auxShuffleArray.addAll(songs);
                    auxShuffleArrayData.addAll(songsData);

                    // shuffling the original array with the Fisher-Yates shuffle algorithm
                    for (int i = 0; i < songs.size(); i++) {
                        int randomIndex =  (int) (Math.random() * (songs.size() - i) + i);

                        Song auxShuffleSong = songs.get(randomIndex);
                        songs.set(randomIndex, songs.get(i));
                        songs.set(i, auxShuffleSong);

                        String[] auxShuffleSongData = songsData.get(randomIndex);
                        songsData.set(randomIndex, songsData.get(i));
                        songsData.set(i, auxShuffleSongData);
                    }

                    queue();
                }
                else {
                    //restore the original order:
                    songs.clear();
                    songsData.clear();
                    songs.addAll(auxShuffleArray);
                    songsData.addAll(auxShuffleArrayData);

                    queue();
                }
            }

            // in case you choose the last song in the list and then suffle (it's going to go to the start of the list, so the button
            // has to be enabled)
            window.setEnabledNextButton(isPlaying && (isLooping || songIndex < songs.size() - 1));
        }
        finally {
            locker.unlock();
        }
    }

    public void loopSongs() {
        try {
            locker.lock();
            isLooping = !isLooping;
            window.setEnabledNextButton(isPlaying && (isLooping || songIndex < songs.size() - 1));
        }
        finally {
            locker.unlock();
        }
    }
    //</editor-fold>
}
