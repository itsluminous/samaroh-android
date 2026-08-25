package com.itsluminous.samaroh.feature.reports.di

import com.itsluminous.samaroh.feature.reports.export.AndroidReportExporter
import com.itsluminous.samaroh.feature.reports.export.ReportExporter
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ReportsFeatureModule {
    @Binds abstract fun bindReportExporter(impl: AndroidReportExporter): ReportExporter
}
