package com.takin1217.dividendvault.data

import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object Research {
    data class Result(
        val cagr: Double?,
        val totalReturn: Double,
        val maxDrawdown: Double,
        val holdings: Int,
        val weights: Map<String, Double>,
    )

    fun yieldTilt(
        db: SQLiteDatabase,
        market: String,
        minYield: Double,
        maxYield: Double,
        feeBps: Double,
        weightCap: Double = 4.0,
        maxHoldings: Int = 0,
        rebalanceDays: Int = 21,
    ): Result {
        val histories = load(db, market)
        if (histories.size < 2) error("$market 일봉이 부족합니다. 2배 일봉 확보를 먼저 실행하세요.")
        val days = masterDates(histories)
        val steps = rebalanceIndexes(days.size, rebalanceDays)
        if (steps.size < 2) error("$market 일봉이 아직 짧아 모의투자를 시작할 수 없습니다.")
        val aligned = align(histories, days)
        var equity = 1.0
        val units = linkedMapOf<String, Double>()
        val curve = mutableListOf<Pair<String, Double>>()
        var next = 0
        var lastWeights = emptyMap<String, Double>()
        val fee = feeBps / 10000.0
        var anomalies = 0
        for (index in steps[0] until days.size) {
            if (next < steps.size && index == steps[next]) {
                val day = days[index]
                val rows = histories.mapNotNull { history ->
                    val yield = featureYield(history, day) ?: return@mapNotNull null
                    history to yield
                }
                if (rows.size >= 2) {
                    var chosen = pick(rows.map { it.second }, minYield, maxYield, minOf(histories.size, rows.size))
                    if (maxHoldings > 0) chosen = chosen.sortedByDescending { rows[it].second }.take(maxHoldings)
                    if (chosen.isNotEmpty()) {
                        val weights = tilt(chosen.map { rows[it].second }, weightCap)
                        val marked = mark(units, aligned, index, countAnomaly = false) { anomalies += 1 }
                        if (marked > 0) equity = marked
                        val old = linkedMapOf<String, Double>()
                        if (units.isNotEmpty() && equity > 0) {
                            units.forEach { (symbol, shares) ->
                                val price = aligned[symbol]?.get(index) ?: 0.0
                                old[symbol] = shares * price / equity
                            }
                        }
                        val fresh = linkedMapOf<String, Double>()
                        chosen.forEachIndexed { position, rowIndex ->
                            val symbol = rows[rowIndex].first.symbol
                            val weight = weights[position]
                            if (weight > 0 && (aligned[symbol]?.get(index) ?: 0.0) > 0) fresh[symbol] = weight
                        }
                        val turnover = if (old.isEmpty()) fresh.values.sum() else {
                            (old.keys + fresh.keys).sumOf { symbol -> abs(fresh.getOrDefault(symbol, 0.0) - old.getOrDefault(symbol, 0.0)) } / 2
                        }
                        equity *= max(0.0, 1 - turnover * fee)
                        units.clear()
                        fresh.forEach { (symbol, weight) ->
                            val price = aligned[symbol]?.get(index) ?: return@forEach
                            if (price > 0) units[symbol] = equity * weight / price
                        }
                        lastWeights = fresh
                    }
                }
                next += 1
            }
            val marked = mark(units, aligned, index, countAnomaly = true) { anomalies += 1 }
            if (marked > 0) {
                equity = marked
                curve += days[index] to equity
            }
        }
        if (curve.size < 2) error("성과를 계산할 만큼 곡선이 길지 않습니다.")
        val startValue = curve.first().second
        val endValue = curve.last().second
        val span = max(1, ChronoUnit.DAYS.between(LocalDate.parse(curve.first().first), LocalDate.parse(curve.last().first)))
        val total = if (startValue > 0) endValue / startValue - 1 else 0.0
        val cagr = if (startValue > 0 && endValue > 0) (endValue / startValue).pow(365.25 / span) - 1 else null
        var peak = startValue
        var drawdown = 0.0
        curve.forEach { (_, value) ->
            peak = max(peak, value)
            if (peak > 0) drawdown = minOf(drawdown, value / peak - 1)
        }
        return Result(cagr, total, drawdown, lastWeights.size, lastWeights)
    }

    private class History(
        val symbol: String,
        val dates: List<String>,
        val close: DoubleArray,
        val adj: DoubleArray,
        val divDates: List<String>,
        val divAmounts: DoubleArray,
    )

    private fun load(db: SQLiteDatabase, market: String): List<History> {
        val symbols = mutableListOf<String>()
        db.rawQuery(
            "SELECT symbol FROM symbols WHERE market=? AND selected=1 AND instrument IN ('EQUITY','ETP') ORDER BY pool_rank",
            arrayOf(market),
        ).use { cursor ->
            while (cursor.moveToNext()) symbols += cursor.getString(0)
        }
        return symbols.mapNotNull { symbol ->
            val dates = mutableListOf<String>()
            val close = mutableListOf<Double>()
            val adj = mutableListOf<Double>()
            db.rawQuery("SELECT date, close, adj_close FROM bars WHERE symbol=? AND close>0 ORDER BY date", arrayOf(symbol)).use { cursor ->
                while (cursor.moveToNext()) {
                    dates += cursor.getString(0)
                    close += cursor.getDouble(1)
                    val adjusted = cursor.getDouble(2)
                    adj += if (adjusted > 0) adjusted else cursor.getDouble(1)
                }
            }
            if (dates.size < 80) return@mapNotNull null
            val divDates = mutableListOf<String>()
            val divAmounts = mutableListOf<Double>()
            db.rawQuery("SELECT ex_date, amount FROM dividends WHERE symbol=? AND amount>0 ORDER BY ex_date", arrayOf(symbol)).use { cursor ->
                while (cursor.moveToNext()) {
                    divDates += cursor.getString(0)
                    divAmounts += cursor.getDouble(1)
                }
            }
            History(symbol, dates, close.toDoubleArray(), adj.toDoubleArray(), divDates, divAmounts.toDoubleArray())
        }
    }

    private fun masterDates(histories: List<History>): List<String> {
        val counts = linkedMapOf<String, Int>()
        histories.forEach { history ->
            history.dates.forEach { day -> counts[day] = (counts[day] ?: 0) + 1 }
        }
        val threshold = if (histories.size < 12) 1 else max(3, histories.size / 4)
        return counts.filterValues { it >= threshold }.keys.sorted()
    }

    private fun rebalanceIndexes(dayCount: Int, step: Int): List<Int> {
        val start = if (dayCount > 400) 252 else max(80, dayCount / 5)
        val stride = step.coerceIn(5, 63)
        return (start until max(start, dayCount - 1) step stride).toList()
    }

    private fun align(histories: List<History>, days: List<String>): Map<String, DoubleArray> {
        return histories.associate { history ->
            val series = DoubleArray(days.size) { Double.NaN }
            var cursor = 0
            var last = Double.NaN
            var lastDay: LocalDate? = null
            days.forEachIndexed { index, day ->
                while (cursor < history.dates.size && history.dates[cursor] < day) cursor += 1
                if (cursor < history.dates.size && history.dates[cursor] == day) {
                    last = history.adj[cursor]
                    lastDay = LocalDate.parse(day)
                    series[index] = last
                } else if (!last.isNaN() && lastDay != null && ChronoUnit.DAYS.between(lastDay, LocalDate.parse(day)) <= 5) {
                    series[index] = last
                } else {
                    last = Double.NaN
                    lastDay = null
                }
            }
            history.symbol to series
        }
    }

    private fun featureYield(history: History, day: String): Double? {
        val index = bisectRight(history.dates, day) - 1
        if (index < 70) return null
        if (ChronoUnit.DAYS.between(LocalDate.parse(history.dates[index]), LocalDate.parse(day)) > 7) return null
        if (index < 63 || history.adj[index - 63] <= 0) return null
        val price = history.close[index]
        if (price <= 0) return null
        val start = LocalDate.parse(history.dates[index]).minusDays(365).toString()
        val left = bisectRight(history.divDates, start)
        val right = bisectRight(history.divDates, history.dates[index])
        var total = 0.0
        for (cursor in left until right) total += history.divAmounts[cursor]
        return max(0.0, total / price)
    }

    private fun pick(yields: List<Double>, minYield: Double, maxYield: Double, minimum: Int): List<Int> {
        val primary = yields.indices.filter { yields[it] in minYield..maxYield }
        if (primary.size >= minimum) return primary
        val spare = yields.indices.filter { it !in primary && yields[it] <= maxYield }.sortedByDescending { yields[it] }
        return (primary + spare).take(minimum)
    }

    private fun tilt(scores: List<Double>, cap: Double = 4.0): List<Double> {
        if (scores.isEmpty()) return emptyList()
        val ranks = ranks(scores)
        val floor = 1 / max(1.0, cap)
        val raw = ranks.map { floor + (1 - floor) * it }
        val total = raw.sum().takeIf { it > 0 } ?: 1.0
        return raw.map { it / total }
    }

    private fun ranks(values: List<Double>): List<Double> {
        if (values.size <= 1) return List(values.size) { 1.0 }
        val order = values.indices.sortedBy { values[it] }
        val ranks = DoubleArray(values.size)
        var cursor = 0
        while (cursor < values.size) {
            var end = cursor
            while (end + 1 < values.size && values[order[end + 1]] == values[order[cursor]]) end += 1
            val rank = ((cursor + end) / 2.0) / (values.size - 1)
            for (position in cursor..end) ranks[order[position]] = rank
            cursor = end + 1
        }
        return ranks.toList()
    }

    private fun mark(
        units: Map<String, Double>,
        aligned: Map<String, DoubleArray>,
        index: Int,
        countAnomaly: Boolean,
        onAnomaly: () -> Unit,
    ): Double {
        var total = 0.0
        units.forEach { (symbol, shares) ->
            val series = aligned[symbol] ?: return@forEach
            var price = series.getOrNull(index) ?: Double.NaN
            val previous = if (index > 0) series[index - 1] else price
            if (price.isNaN()) price = previous
            if (price.isNaN() || previous.isNaN() || previous <= 0) return@forEach
            if (price / previous - 1 > 5 || price / previous - 1 < -0.9) {
                if (countAnomaly) onAnomaly()
                price = previous
            }
            total += shares * price
        }
        return total
    }

    private fun bisectRight(values: List<String>, key: String): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] <= key) low = mid + 1 else high = mid
        }
        return low
    }
}
