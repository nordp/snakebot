package se.cygni.snake

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.util.concurrent.ListenableFuture
import org.springframework.web.socket.WebSocketSession
import se.cygni.snake.api.event.*
import se.cygni.snake.api.exception.InvalidPlayerName
import se.cygni.snake.api.model.GameMode
import se.cygni.snake.api.model.GameSettings
import se.cygni.snake.api.model.PlayerPoints
import se.cygni.snake.api.model.SnakeDirection
import se.cygni.snake.api.response.PlayerRegistered
import se.cygni.snake.api.util.GameSettingsUtils
import se.cygni.snake.client.AnsiPrinter
import se.cygni.snake.client.BaseSnakeClient
import se.cygni.snake.client.MapCoordinate
import se.cygni.snake.client.MapUtil

import java.util.*

class SnakePlayer : BaseSnakeClient() {
    private val ansiPrinter = AnsiPrinter(ANSI_PRINTER_ACTIVE, true)

    private var lastDirection: SnakeDirection? = null

    override fun onMapUpdate(mapUpdateEvent: MapUpdateEvent) {
        ansiPrinter.printMap(mapUpdateEvent)
        val mapUtil = MapUtil(mapUpdateEvent.map, playerId)
        val height = mapUpdateEvent.map.height
        val width = mapUpdateEvent.map.width
        val border = 6

        var chosenDirection: SnakeDirection? = SnakeDirection.DOWN
        val directions = ArrayList<SnakeDirection>()

        chosenDirection = lastDirection

        //Turn inwards
        if (mapUtil.myPosition.x > width - width / border && lastDirection != SnakeDirection.DOWN) {
            chosenDirection = if (mapUtil.canIMoveInDirection(SnakeDirection.LEFT)) SnakeDirection.LEFT else SnakeDirection.DOWN
        }
        if (mapUtil.myPosition.x < width / border && lastDirection != SnakeDirection.UP) {
            chosenDirection = if (mapUtil.canIMoveInDirection(SnakeDirection.RIGHT)) SnakeDirection.RIGHT else SnakeDirection.UP
        }
        if (mapUtil.myPosition.y > height - height / border && lastDirection != SnakeDirection.LEFT) {
            chosenDirection = if (mapUtil.canIMoveInDirection(SnakeDirection.UP)) SnakeDirection.UP else SnakeDirection.LEFT
        }
        if (mapUtil.myPosition.y < height / border && lastDirection != SnakeDirection.RIGHT) {
            chosenDirection = if (mapUtil.canIMoveInDirection(SnakeDirection.DOWN)) SnakeDirection.DOWN else SnakeDirection.RIGHT
        }

        //Evade
        for (direction in SnakeDirection.values()) {
            if (mapUtil.canIMoveInDirection(direction)) {
                directions.add(direction)
            }
        }

        if (directions.size == 1)
            chosenDirection = directions[0]
        else {
            val valuedDirections = mutableSetOf<Pair<SnakeDirection, Int>>()
            for (direction in directions) {
                valuedDirections.add(Pair(direction, calculateSpace(direction, mapUtil)))
            }
            if (!valuedDirections.isEmpty())
                chosenDirection = valuedDirections.sortedBy { it.second }.last().first
        }



        // Register action here!
        lastDirection = chosenDirection

        println("TURNING: " + chosenDirection)
        println()

        registerMove(mapUpdateEvent.gameTick, chosenDirection)
    }

    private fun calculateSpace(direction: SnakeDirection, mapUtil: MapUtil): Int {
        val pos = mapUtil.myPosition
        var currDir = direction;
        var currPos = pos;
        var lastPos = pos;
        var maxX = pos.x;
        var maxY = pos.y;
        var minX = pos.x;
        var minY = pos.y;
        var i = 0;
        do {
            do {
                lastPos = currPos;
                currPos = currPos.translateBy(currDir)



                if (currPos.x > maxX)
                    maxX = currPos.x
                if (currPos.y > maxY)
                    maxY = currPos.y
                if (currPos.x < minX)
                    minX = currPos.x
                if (currPos.y < minY)
                    minY = currPos.y
            } while (mapUtil.isTileAvailableForMovementTo(currPos))
            currPos = lastPos;
            currDir = currDir.turnLeft();

            i++
        } while (i < 1000)

        println("Size for " + direction + ": " + (maxX-minX) * (maxY-minY))

        return (maxX-minX) * (maxY-minY)
    }

    private fun MapCoordinate.translateBy(direction: SnakeDirection): MapCoordinate{
        var dx = 0;
        var dy = 0;
        if (direction == SnakeDirection.DOWN){
            dy = 1
        } else if (direction == SnakeDirection.UP){
            dy = -1
        } else if (direction == SnakeDirection.RIGHT){
            dx = 1
        } else if (direction == SnakeDirection.LEFT){
            dx = -1
        }
        return translateBy(dx,dy)
    }

    private fun SnakeDirection.turnLeft(): SnakeDirection {
        if (this.equals(SnakeDirection.UP))
            return SnakeDirection.LEFT;
        if (this.equals(SnakeDirection.LEFT))
            return SnakeDirection.DOWN;
        if (this.equals(SnakeDirection.DOWN))
            return SnakeDirection.RIGHT;
        return SnakeDirection.UP;
    }

    override fun onInvalidPlayerName(invalidPlayerName: InvalidPlayerName) {
        LOGGER.debug("InvalidPlayerNameEvent: " + invalidPlayerName)
    }

    override fun onSnakeDead(snakeDeadEvent: SnakeDeadEvent) {
        LOGGER.info("A snake {} died by {}",
                snakeDeadEvent.playerId,
                snakeDeadEvent.deathReason)
    }

    override fun onGameResult(gameResultEvent: GameResultEvent) {
        LOGGER.info("Game result:")
        gameResultEvent.playerRanks.forEach { playerRank -> LOGGER.info(playerRank.toString()) }
    }

    override fun onGameEnded(gameEndedEvent: GameEndedEvent) {
        LOGGER.debug("GameEndedEvent: " + gameEndedEvent)
    }

    override fun onGameStarting(gameStartingEvent: GameStartingEvent) {
        LOGGER.debug("GameStartingEvent: " + gameStartingEvent)
    }

    override fun onPlayerRegistered(playerRegistered: PlayerRegistered) {
        LOGGER.info("PlayerRegistered: " + playerRegistered)

        if (AUTO_START_GAME) {
            startGame()
        }
    }

    override fun onTournamentEnded(tournamentEndedEvent: TournamentEndedEvent) {
        LOGGER.info("Tournament has ended, winner playerId: {}", tournamentEndedEvent.playerWinnerId)
        var c = 1
        for (pp in tournamentEndedEvent.gameResult) {
            LOGGER.info("{}. {} - {} points", c++, pp.name, pp.points)
        }
    }

    override fun onGameLink(gameLinkEvent: GameLinkEvent) {
        LOGGER.info("The game can be viewed at: {}", gameLinkEvent.url)
    }

    override fun onSessionClosed() {
        LOGGER.info("Session closed")
    }

    override fun onConnected() {
        LOGGER.info("Connected, registering for training...")
        val gameSettings = GameSettingsUtils.trainingWorld()
        registerForGame(gameSettings)
    }

    override fun getName(): String {
        return SNAKE_NAME
    }

    override fun getServerHost(): String {
        return SERVER_NAME
    }

    override fun getServerPort(): Int {
        return SERVER_PORT
    }

    override fun getGameMode(): GameMode {
        return GAME_MODE
    }

    companion object {

        private val LOGGER = LoggerFactory.getLogger(SnakePlayer::class.java)

        // Set to false if you want to start the game from a GUI
        private val AUTO_START_GAME = true

        // Personalise your game ...
        private val SERVER_NAME = "snake.cygni.se"
        private val SERVER_PORT = 80

        private val GAME_MODE = GameMode.TRAINING
        private val SNAKE_NAME = "Flipper"

        // Set to false if you don't want the game world printed every game tick.
        private val ANSI_PRINTER_ACTIVE = false

        @JvmStatic
        fun main(args: Array<String>) {
            val simpleSnakePlayer = SnakePlayer()

            try {
                val connect = simpleSnakePlayer.connect()
                connect.get()
            } catch (e: Exception) {
                LOGGER.error("Failed to connect to server", e)
                System.exit(1)
            }

            startTheSnake(simpleSnakePlayer)
        }

        /**
         * The Snake client will continue to run ...
         * : in TRAINING mode, until the single game ends.
         * : in TOURNAMENT mode, until the server tells us its all over.
         */
        private fun startTheSnake(simpleSnakePlayer: SnakePlayer) {
            val task = {
                do {
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }

                } while (simpleSnakePlayer.isPlaying)

                LOGGER.info("Shutting down")
            }

            val thread = Thread(task)
            thread.start()
        }
    }
}
