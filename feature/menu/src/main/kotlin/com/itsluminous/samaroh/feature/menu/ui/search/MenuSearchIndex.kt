package com.itsluminous.samaroh.feature.menu.ui.search

import androidx.annotation.StringRes
import com.itsluminous.samaroh.core.i18n.R

/** A Menu-tab nested destination a search result can open (routes map in MenuNavGraph). */
enum class MenuScreenTarget {
    SETTINGS,
    LANGUAGE,
    REMINDERS,
    SYNC_STATUS,
    BUSINESS_PROFILE,
    EVENT_TYPES,
    MEMBERS,
    ABOUT,
}

/** Where tapping a search result lands. */
sealed interface MenuSearchTarget {
    /** A screen of the Menu tab's nested graph. */
    data class Screen(
        val screen: MenuScreenTarget,
    ) : MenuSearchTarget

    /**
     * The Reports section: home when [reportRouteArg] is null, else the named report's
     * detail screen. The arg is `feature:reports`' `ReportType.routeArg` wire value —
     * feature modules never depend on each other (AGENTS), so the string travels to the
     * app shell, which owns both graphs (consistency is tested there).
     */
    data class Report(
        val reportRouteArg: String?,
    ) : MenuSearchTarget

    /** The sign-out flow (confirmation dialog on the Menu home). */
    data object SignOut : MenuSearchTarget
}

/**
 * One searchable Menu destination: a localized title, an optional localized context
 * line (the screen it lives on — doubles as the result's breadcrumb) and extra
 * localized keyword resources. All texts index in EVERY supported locale (ADR-075),
 * so an English query finds rows while the app runs in Hindi and vice versa.
 */
data class MenuSearchEntry(
    /** Stable id for tests and result keys. */
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val contextRes: Int? = null,
    val target: MenuSearchTarget,
    val keywordRes: List<Int> = emptyList(),
    /** Owner-gated destinations (§4.4) hide from results for non-owners. */
    val ownerOnly: Boolean = false,
    /** Sign-out only exists with a signed-in session. */
    val requiresSignedIn: Boolean = false,
)

/**
 * The STATIC index of every Menu-tab destination (ADR-075): all Settings rows and
 * sections, Members, the About rows, each report and sign-out. Destinations, not
 * data — bookings/expenses/items have their own tabs' search affordances.
 */
object MenuSearchIndex {
    /** `ReportType.routeArg` wire values, in the reports home order (tested in :app). */
    val REPORT_ROUTE_ARGS: List<String> =
        listOf(
            "revenue",
            "dues_aging",
            "occupancy",
            "event_types",
            "sources",
            "expense_summary",
            "profit",
            "inventory_valuation",
            "collection",
            "personal_expenses",
        )

    @StringRes
    private fun reportTitleRes(routeArg: String): Int =
        when (routeArg) {
            "revenue" -> R.string.reports_report_revenue
            "dues_aging" -> R.string.reports_report_dues_aging
            "occupancy" -> R.string.reports_report_occupancy
            "event_types" -> R.string.reports_report_event_types
            "sources" -> R.string.reports_report_sources
            "expense_summary" -> R.string.reports_report_expense_summary
            "profit" -> R.string.reports_report_profit
            "inventory_valuation" -> R.string.reports_report_inventory_valuation
            "collection" -> R.string.reports_report_collection
            "personal_expenses" -> R.string.reports_report_personal_expenses
            else -> error("unknown report route arg: $routeArg")
        }

    @StringRes
    private fun reportSubtitleRes(routeArg: String): Int =
        when (routeArg) {
            "revenue" -> R.string.reports_report_revenue_subtitle
            "dues_aging" -> R.string.reports_report_dues_aging_subtitle
            "occupancy" -> R.string.reports_report_occupancy_subtitle
            "event_types" -> R.string.reports_report_event_types_subtitle
            "sources" -> R.string.reports_report_sources_subtitle
            "expense_summary" -> R.string.reports_report_expense_summary_subtitle
            "profit" -> R.string.reports_report_profit_subtitle
            "inventory_valuation" -> R.string.reports_report_inventory_valuation_subtitle
            "collection" -> R.string.reports_report_collection_subtitle
            "personal_expenses" -> R.string.reports_report_personal_expenses_subtitle
            else -> error("unknown report route arg: $routeArg")
        }

    val entries: List<MenuSearchEntry> =
        listOf(
            // Menu home sections.
            MenuSearchEntry(
                id = "settings",
                titleRes = R.string.menu_section_settings,
                target = MenuSearchTarget.Screen(MenuScreenTarget.SETTINGS),
                keywordRes = listOf(R.string.menu_section_settings_subtitle),
            ),
            MenuSearchEntry(
                id = "reports",
                titleRes = R.string.menu_section_reports,
                target = MenuSearchTarget.Report(reportRouteArg = null),
                keywordRes = listOf(R.string.menu_section_reports_subtitle),
            ),
            MenuSearchEntry(
                id = "members",
                titleRes = R.string.menu_section_members,
                target = MenuSearchTarget.Screen(MenuScreenTarget.MEMBERS),
                keywordRes = listOf(R.string.menu_section_members_subtitle, R.string.menu_members_add),
                ownerOnly = true,
            ),
            MenuSearchEntry(
                id = "about",
                titleRes = R.string.menu_section_about,
                target = MenuSearchTarget.Screen(MenuScreenTarget.ABOUT),
                keywordRes = listOf(R.string.menu_section_about_subtitle),
            ),
            MenuSearchEntry(
                id = "sign_out",
                titleRes = R.string.menu_identity_sign_out,
                target = MenuSearchTarget.SignOut,
                requiresSignedIn = true,
            ),
            // Settings rows/sections (§4.4).
            MenuSearchEntry(
                id = "language",
                titleRes = R.string.settings_language_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.LANGUAGE),
                keywordRes =
                    listOf(
                        R.string.settings_language_name_en,
                        R.string.settings_language_name_hi,
                        R.string.settings_language_system,
                    ),
            ),
            MenuSearchEntry(
                id = "theme",
                titleRes = R.string.settings_theme_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.SETTINGS),
                keywordRes =
                    listOf(
                        R.string.settings_theme_system,
                        R.string.settings_theme_light,
                        R.string.settings_theme_dark,
                    ),
            ),
            MenuSearchEntry(
                id = "dynamic_color",
                titleRes = R.string.settings_theme_dynamic_color,
                contextRes = R.string.settings_theme_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.SETTINGS),
            ),
            MenuSearchEntry(
                id = "reminders",
                titleRes = R.string.settings_reminders_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.REMINDERS),
                keywordRes =
                    listOf(
                        R.string.settings_reminders_subtitle,
                        R.string.settings_reminders_style_notification,
                        R.string.settings_reminders_style_fullscreen,
                        R.string.settings_reminders_style_fullscreen_always,
                    ),
            ),
            MenuSearchEntry(
                id = "reminder_sound",
                titleRes = R.string.settings_reminders_sound_title,
                contextRes = R.string.settings_reminders_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.REMINDERS),
                keywordRes = listOf(R.string.settings_reminders_sound_default),
            ),
            MenuSearchEntry(
                id = "booking_form",
                titleRes = R.string.settings_booking_form_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.SETTINGS),
                keywordRes =
                    listOf(
                        R.string.settings_booking_form_show_deposit,
                        R.string.settings_booking_form_show_source,
                        R.string.settings_booking_form_show_times,
                    ),
            ),
            MenuSearchEntry(
                id = "booking_calendar",
                titleRes = R.string.settings_booking_calendar_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.SETTINGS),
                keywordRes = listOf(R.string.settings_booking_calendar_icon_alpha_label),
            ),
            MenuSearchEntry(
                id = "image_quality",
                titleRes = R.string.settings_image_quality_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.SETTINGS),
                keywordRes =
                    listOf(
                        R.string.settings_image_quality_bills_label,
                        R.string.settings_image_quality_inventory_label,
                    ),
            ),
            MenuSearchEntry(
                id = "google",
                titleRes = R.string.settings_google_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.SETTINGS),
                keywordRes = listOf(R.string.settings_google_link),
            ),
            MenuSearchEntry(
                id = "gcal",
                titleRes = R.string.settings_gcal_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.SETTINGS),
                keywordRes = listOf(R.string.settings_gcal_subtitle),
            ),
            MenuSearchEntry(
                id = "backup",
                titleRes = R.string.settings_backup_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.SETTINGS),
                keywordRes =
                    listOf(
                        R.string.settings_backup_frequency_title,
                        R.string.settings_backup_backup_now,
                    ),
                ownerOnly = true,
            ),
            MenuSearchEntry(
                id = "sync_status",
                titleRes = R.string.settings_sync_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.SYNC_STATUS),
                keywordRes = listOf(R.string.settings_sync_sync_now),
            ),
            MenuSearchEntry(
                id = "business_profile",
                titleRes = R.string.settings_business_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.BUSINESS_PROFILE),
                keywordRes =
                    listOf(
                        R.string.settings_business_name,
                        R.string.settings_business_address,
                    ),
            ),
            MenuSearchEntry(
                id = "event_types",
                titleRes = R.string.settings_event_types_title,
                contextRes = R.string.settings_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.EVENT_TYPES),
                // Built-in event-type NAMES are part of the destination's identity
                // ("wedding" should find where event types are managed); user-created
                // types are data, which the index deliberately excludes (ADR-075).
                keywordRes =
                    listOf(
                        R.string.booking_event_type_engagement,
                        R.string.booking_event_type_tilak,
                        R.string.booking_event_type_wedding,
                        R.string.booking_event_type_room_booking,
                        R.string.booking_event_type_birthday,
                        R.string.booking_event_type_anniversary,
                    ),
                ownerOnly = true,
            ),
            // About rows (§4.4).
            MenuSearchEntry(
                id = "about_version",
                titleRes = R.string.menu_search_about_version,
                contextRes = R.string.menu_about_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.ABOUT),
                keywordRes = listOf(R.string.common_app_name),
            ),
            MenuSearchEntry(
                id = "about_source",
                titleRes = R.string.menu_about_source_code,
                contextRes = R.string.menu_about_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.ABOUT),
            ),
            MenuSearchEntry(
                id = "about_licenses",
                titleRes = R.string.menu_about_licenses,
                contextRes = R.string.menu_about_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.ABOUT),
            ),
            MenuSearchEntry(
                id = "about_donate",
                titleRes = R.string.menu_about_donate_upi,
                contextRes = R.string.menu_about_title,
                target = MenuSearchTarget.Screen(MenuScreenTarget.ABOUT),
                keywordRes = listOf(R.string.menu_about_donate_upi_summary),
            ),
        ) +
            // The ten reports (§4.4), each deep-linking to its detail screen.
            REPORT_ROUTE_ARGS.map { arg ->
                MenuSearchEntry(
                    id = "report_$arg",
                    titleRes = reportTitleRes(arg),
                    contextRes = R.string.menu_section_reports,
                    target = MenuSearchTarget.Report(arg),
                    keywordRes = listOf(reportSubtitleRes(arg)),
                )
            }
}
