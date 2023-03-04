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
    private final ActionListener buttonListenerNext = e -> {};
    private final ActionListener buttonListenerPrevious = e -> {};
    private final ActionListener buttonListenerShuffle = e -> {};
    private final ActionListener buttonListenerLoop = e -> {};
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
        }

        @Override
        public void mousePressed(MouseEvent e) {
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
        String selectedSong = window.getSelectedSong();

        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).getUuid() == selectedSong) {
                return i;
            }
        }

        return 0;
    }

    public void queue()
    {
        String[][] data = new String[this.songsData.size()][7];
        this.playlist = this.songsData.toArray(data);
        window.setQueueList(this.playlist);
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

                        currentSongTime = (int) (count * currentSong.getMsPerFrame());
                        totalSongTime = (int) currentSong.getMsLength();
                        window.setTime(currentSongTime, totalSongTime);

                        if (window.getScrubberValue() < currentSong.getMsLength()) //while song's not ended yet
                        {isPlaying= playNextFrame();}
                        else
                        {stopSong();}
                        count++;
                    }
                    catch (JavaLayerException e) {}
                }

                if(stopPlaying)
                {
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
        playSong(songIndex);
    }

    public void playSong(int songIndex)
    {
        currentFrame = 0;
        count = 0;

        if (isPlaying) stopPlaying = true;

        try
        {
            locker.lock();

            isPlaying = true;
            currentSong = songs.get(songIndex);

            window.setPlayingSongInfo(currentSong.getTitle(), currentSong.getAlbum(), currentSong.getArtist());
            window.setPlayPauseButtonIcon(isPaused ? 0 : 1);
            window.setEnabledScrubber(isPlaying); //**
            window.setEnabledPlayPauseButton(isPlaying);
            window.setEnabledStopButton(isPlaying);

            isPaused = false;

            try
            {
                device = FactoryRegistry.systemRegistry().createAudioDevice();
                device.open(decoder = new Decoder());
                bitstream = new Bitstream(currentSong.getBufferedInputStream());
                songLoop();
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

            int songToRemoveIndex = getSongIndex();

            if(songToRemoveIndex == songIndex) {stopSong();}
            if(songToRemoveIndex < songToRemoveIndex) {songIndex--;}

            songs.remove(songToRemoveIndex);
            songsData.remove(songToRemoveIndex);

            queue();
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
        isPlaying = isPaused;
        isPaused = !isPaused;

        window.setPlayPauseButtonIcon(isPaused ? 0 : 1);
    }

    public void stopSong() {
        isPlaying = false;
        stopPlaying = true;
        count = 0;

        window.resetMiniPlayer();
    }
    //</editor-fold>
}
