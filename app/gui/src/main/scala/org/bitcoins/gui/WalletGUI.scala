package org.bitcoins.gui

import javafx.event.{ActionEvent, EventHandler}
import javafx.scene.image.Image
import org.bitcoins.cli.CliCommand.GetInfo
import org.bitcoins.cli.ConsoleCli
import org.bitcoins.commons.jsonmodels.BitcoinSServerInfo
import org.bitcoins.core.config.{MainNet, RegTest, SigNet, TestNet3}
import org.bitcoins.gui.dlc.DLCPane
import org.bitcoins.gui.settings.SettingsPane
import scalafx.application.{JFXApp, Platform}
import scalafx.beans.property.StringProperty
import scalafx.geometry.{Insets, Pos}
import scalafx.scene.Scene
import scalafx.scene.control.Alert.AlertType
import scalafx.scene.control.TabPane.TabClosingPolicy
import scalafx.scene.control._
import scalafx.scene.layout.{BorderPane, HBox, StackPane, VBox}

import scala.util.{Failure, Success}

object WalletGUI extends JFXApp {
  // Catch unhandled exceptions on FX Application thread
  Thread
    .currentThread()
    .setUncaughtExceptionHandler(
      new Thread.UncaughtExceptionHandler {

        override def uncaughtException(t: Thread, ex: Throwable): Unit = {
          ex.printStackTrace()
          val _ = new Alert(AlertType.Error) {
            initOwner(owner)
            title = "Unhandled exception"
            headerText = "Exception: " + ex.getClass + ""
            contentText = Option(ex.getMessage).getOrElse("")
          }.showAndWait()
        }
      }
    )

  private val argsWithIndex = parameters.raw.zipWithIndex

  val rpcPortOpt: Option[Int] = {
    val portOpt = argsWithIndex.find(_._1.toLowerCase == "--rpcport")
    portOpt.map { case (_, idx) =>
      parameters.raw(idx + 1).toInt
    }
  }

  GlobalData.rpcPortOpt = rpcPortOpt

  val debug: Boolean = {
    parameters.raw.exists(_.toLowerCase == "--debug")
  }

  GlobalData.debug = debug

  private val glassPane = new VBox {
    children = new ProgressIndicator {
      progress = ProgressIndicator.IndeterminateProgress
      visible = true
    }
    alignment = Pos.Center
    visible = false
  }

  private val statusLabel = new Label {
    maxWidth = Double.MaxValue
    padding = Insets(0, 10, 10, 10)
    text <== GlobalData.statusText
  }

  private val resultArea = new TextArea {
    editable = false
    wrapText = true
    text <== StringProperty(
      "Your current balance is: ") + GlobalData.currentBalance + StringProperty(
      s" sats\n\n${(0 until 60).map(_ => "-").mkString}\n\n") + GlobalData.log
  }

  private val model = new WalletGUIModel()

  private val getNewAddressButton = new Button {
    text = "Get New Address"
    onAction = new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent): Unit = model.onGetNewAddress()
    }
  }

  private val sendButton = new Button {
    text = "Send"
    onAction = new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent): Unit = model.onSend()
    }
  }

  private val buttonBar = new HBox {
    children = Seq(getNewAddressButton, sendButton)
    alignment = Pos.Center
    spacing <== width / 2
  }

  private val borderPane = new BorderPane {
    top = buttonBar
    center = resultArea
    bottom = statusLabel
  }

  private val dlcPane = new DLCPane(glassPane)

  private val settingsPane = new SettingsPane

  private val tabPane: TabPane = new TabPane {

    val walletTab: Tab = new Tab {
      text = "Wallet"
      content = borderPane
    }

    val dlcTab: Tab = new Tab {
      text = "DLC"
      content = dlcPane.borderPane
    }

    val settingsTab: Tab = new Tab {
      text = "Settings"
      content = settingsPane.view
    }

    tabs = Seq(walletTab, dlcTab, settingsTab)

    tabClosingPolicy = TabClosingPolicy.Unavailable
  }

  private val rootView = new StackPane {
    children = Seq(
      tabPane,
      glassPane
    )
  }

  private val walletScene = new Scene(1000, 600) {
    root = rootView
    stylesheets = GlobalData.currentStyleSheets
  }

  val info: BitcoinSServerInfo =
    ConsoleCli.exec(GetInfo, GlobalData.consoleCliConfig) match {
      case Failure(exception) =>
        throw exception
      case Success(str) =>
        val json = ujson.read(str)
        BitcoinSServerInfo.fromJson(json)
    }

  GlobalData.network = info.network

  val (img, titleStr): (Image, String) = info.network match {
    case MainNet =>
      (new Image("/icons/bitcoin-s.png"), "Bitcoin-S Wallet")
    case TestNet3 =>
      (new Image("/icons/bitcoin-s-testnet.png"),
       "Bitcoin-S Wallet - [testnet]")
    case RegTest =>
      (new Image("/icons/bitcoin-s-regtest.png"),
       "Bitcoin-S Wallet - [regtest]")
    case SigNet =>
      (new Image("/icons/bitcoin-s-signet.png"), "Bitcoin-S Wallet - [signet]")

  }

  stage = new JFXApp.PrimaryStage {
    title = titleStr
    scene = walletScene
    icons.add(img)
  }

  private val taskRunner = new TaskRunner(resultArea, glassPane)
  model.taskRunner = taskRunner

  Platform.runLater(sendButton.requestFocus())

  override def stopApp(): Unit = {
    sys.exit(0) // Kills the server if GUI is closed in AppBundle
  }
}
