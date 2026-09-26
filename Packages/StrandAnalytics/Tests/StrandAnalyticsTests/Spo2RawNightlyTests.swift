import XCTest
import WhoopProtocol
import WhoopStore
@testable import StrandAnalytics

/// `nightlySpo2RawMeans` — the WHOOP 4.0 raw red/IR ADC means behind the Health "Raw SpO₂" tile (#93).
/// Byte-parity twin of the Kotlin `NightlySpo2RawTest`.
///
/// The same `spo2Sample` table carries the Oura ring's single-channel rows (`red` = the ring's reading,
/// `ir = 0`). Those used to be averaged in, so a ring night reported an IR mean of 0 beside a red mean of
/// ~97 and the tile's `(red + ir) / 2` showed a ~97 % reading as "~49 ADC". Only two-channel rows count.
final class Spo2RawNightlyTests: XCTestCase {

    private func session(_ start: Int, _ end: Int) -> SleepSession {
        SleepSession(start: start, end: end, efficiency: 0.9, stages: [], restingHR: 55, avgHRV: 60)
    }

    private func whoop(_ ts: Int, red: Int, ir: Int) -> SpO2Sample {
        SpO2Sample(ts: ts, red: red, ir: ir, unit: "raw_adc")
    }

    /// An Oura row exactly as `OuraStreamMapping` writes it.
    private func ring(_ ts: Int, _ value: Int) -> SpO2Sample { SpO2Sample(ts: ts, red: value, ir: 0, unit: "raw") }

    func testInWindowTwoChannelSamplesAverageRedAndIrSeparately() {
        let r = AnalyticsEngine.nightlySpo2RawMeans(
            [session(1000, 2000)],
            spo2: [whoop(1100, red: 30000, ir: 20000), whoop(1500, red: 32000, ir: 24000)])
        XCTAssertEqual(r?.red, 31000)
        XCTAssertEqual(r?.ir, 22000)
    }

    func testRingOnlyNightHasNoRawMeans() {
        let r = AnalyticsEngine.nightlySpo2RawMeans(
            [session(1000, 2000)], spo2: [ring(1100, 97), ring(1200, 98), ring(1300, 96)])
        XCTAssertNil(r, "a ring has no red/IR ADC pair, so there is no raw mean to report")
    }

    /// A legacy pre-#2152 `dc_raw` row (stored with ir = 0) is single-channel too, so it cannot inflate
    /// the mean either.
    func testLegacyPerfusionRowsAreExcluded() {
        XCTAssertNil(AnalyticsEngine.nightlySpo2RawMeans([session(1000, 2000)], spo2: [ring(1100, 11_709_098)]))
    }

    func testSingleChannelRowsDoNotDiluteTwoChannelMeans() {
        let r = AnalyticsEngine.nightlySpo2RawMeans(
            [session(1000, 2000)],
            spo2: [whoop(1100, red: 100, ir: 200), ring(1200, 97), whoop(1300, red: 300, ir: 400)])
        XCTAssertEqual(r?.red, 200, "(100 + 300) / 2 — the ring row must not count")
        XCTAssertEqual(r?.ir, 300)
    }

    /// The ring's SpO2 is not lost by this: the same rows still feed its own nightly path.
    func testRingRowsStillReachTheCeilingMean() {
        let rows = [ring(1100, 97), ring(1200, 99)]
        XCTAssertNil(AnalyticsEngine.nightlySpo2RawMeans([session(1000, 2000)], spo2: rows))
        XCTAssertEqual(AnalyticsEngine.nightlySpo2CeilingMean([session(1000, 2000)], spo2: rows)?.mean, 98)
    }

    func testBoundariesInclusiveAndOutOfWindowDropped() {
        let r = AnalyticsEngine.nightlySpo2RawMeans(
            [session(1000, 2000)],
            spo2: [whoop(999, red: 9, ir: 9), whoop(1000, red: 100, ir: 200),
                   whoop(2000, red: 300, ir: 400), whoop(2001, red: 9, ir: 9)])
        XCTAssertEqual(r?.red, 200)
        XCTAssertEqual(r?.ir, 300)
    }

    func testEmptyInputsReturnNil() {
        XCTAssertNil(AnalyticsEngine.nightlySpo2RawMeans([], spo2: [whoop(100, red: 1, ir: 1)]))
        XCTAssertNil(AnalyticsEngine.nightlySpo2RawMeans([session(0, 1000)], spo2: []))
    }
}
