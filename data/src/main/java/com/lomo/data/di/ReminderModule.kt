package com.lomo.data.di

import com.lomo.data.reminder.AlarmManagerReminderCoordinator
import com.lomo.data.reminder.AlarmManagerReminderScheduler
import com.lomo.data.reminder.MemoMutationReminderScheduler
import com.lomo.data.reminder.ReminderAsyncRunner
import com.lomo.data.reminder.ReminderNotifier
import com.lomo.domain.repository.ReminderCoordinator
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.core.qualifier.named

val reminderModule = module {
    single {
        AlarmManagerReminderScheduler(
            context = androidContext(),
            memoQueryRepository = get(),
        )
    } bind MemoMutationReminderScheduler::class
    single {
        AlarmManagerReminderCoordinator(
            scheduler = get(),
            memoQueryRepository = get(),
            memoMutationRepository = get(),
        )
    } bind ReminderCoordinator::class
    single { ReminderAsyncRunner(get(named("ApplicationScope"))) }
    single { ReminderNotifier(androidContext()) }
}
