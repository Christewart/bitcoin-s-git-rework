package org.bitcoins.chain

import org.bitcoins.testkit.util.BitcoinSUnitTest
import org.bitcoins.core.config.TestNet3
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.config.MainNet
import org.bitcoins.chain.config.ChainAppConfig
import java.nio.file.Files
import ch.qos.logback.classic.Level

class ChainAppConfigTest extends BitcoinSUnitTest {
  val tempDir = Files.createTempDirectory("bitcoin-s")
  val config = ChainAppConfig(directory = tempDir)

  it must "be overridable" in {
    assert(config.network == RegTest)

    val otherConf = ConfigFactory.parseString("bitcoin-s.network = testnet3")
    val withOther: ChainAppConfig = config.withOverrides(otherConf)
    assert(withOther.network == TestNet3)

    val mainnetConf = ConfigFactory.parseString("bitcoin-s.network = mainnet")
    val mainnet: ChainAppConfig = withOther.withOverrides(mainnetConf)
    assert(mainnet.network == MainNet)
  }

  it must "be overridable with multiple levels" in {
    val testnet = ConfigFactory.parseString("bitcoin-s.network = testnet3")
    val mainnet = ConfigFactory.parseString("bitcoin-s.network = mainnet")
    val overriden: ChainAppConfig = config.withOverrides(testnet, mainnet)
    assert(overriden.network == MainNet)

  }

  it must "have user data directory configuration take precedence" in {

    val tempDir = Files.createTempDirectory("bitcoin-s")
    val tempFile = Files.createFile(tempDir.resolve("bitcoin-s.conf"))
    val confStr = """
    | bitcoin-s {
    |   network = testnet3
    |   
    |   logging {
    |     level = off
    |
    |     p2p = warn
    |   }
    | }
    """.stripMargin
    val _ = Files.write(tempFile, confStr.getBytes())

    val appConfig = ChainAppConfig(directory = tempDir)

    assert(appConfig.datadir == tempDir.resolve("testnet3"))
    assert(appConfig.network == TestNet3)
    assert(appConfig.logLevel == Level.OFF)
    assert(appConfig.p2pLogLevel == Level.WARN)
  }
}
