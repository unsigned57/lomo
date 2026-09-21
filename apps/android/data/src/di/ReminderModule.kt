package com.lomo.data.di

import com.lomo.data.reminder.AlarmManagerReminderCoordinator
import com.lomo.data.reminder.AlarmManagerReminderScheduler
import com.lomo.data.reminder.AlarmSchedulePort
import com.lomo.data.reminder.AndroidAlarmManagerGateway
import com.lomo.data.reminder.AndroidAlarmSchedulePort
import com.lomo.data.reminder.MemoMutationReminderScheduler
import com.lomo.data.reminder.OwnerReminderTokenFactory
import com.lomo.data.reminder.ReminderAsyncRunner
import com.lomo.data.reminder.ReminderExecutionLedger
import com.lomo.data.reminder.ReminderNotifier
import com.lomo.data.reminder.ReminderRollingWindowScheduler
import com.lomo.data.reminder.ReminderRebuildDemand
import com.lomo.data.reminder.ReminderRebuildWorker
import com.lomo.data.reminder.WorkManagerReminderRebuildDemand
import com.lomo.domain.repository.ReminderCoordinator
import com.lomo.domain.repository.ReminderTokenFactory
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.bind
import org.koin.core.qualifier.named

val reminderModule = module {
    single<AlarmSchedulePort> {
        AndroidAlarmSchedulePort(gateway = AndroidAlarmManagerGateway(androidContext()))
    }
    single { ReminderExecutionLedger(androidContext()) }
    single { ReminderRollingWindowScheduler(port = get(), ledger = get()) }
    single {
        AlarmManagerReminderScheduler(
            context = androidContext(),
            storePort = get(),
            readiness = get(),
            schedulePort = get(),
            rollingWindow = get(),
        )
    } bind MemoMutationReminderScheduler::class
    single<ReminderTokenFactory> { OwnerReminderTokenFactory() }
    single {
        AlarmManagerReminderCoordinator(
            scheduler = get(),
            memoQueryRepository = get(),
            markdownReminderRepository = get(),
            memoMutationRepository = get(),
            reminderTokenFactory = get(),
        )
    } bind ReminderCoordinator::class
    single { ReminderAsyncRunner(get(named("ApplicationScope"))) }
    single<ReminderRebuildDemand> { WorkManagerReminderRebuildDemand(androidContext()) }
    workerOf(::ReminderRebuildWorker)
    single { ReminderNotifier(androidContext(), get()) }
}
