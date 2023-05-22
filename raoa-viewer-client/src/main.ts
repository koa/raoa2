import {enableProdMode} from '@angular/core';
import {platformBrowserDynamic} from '@angular/platform-browser-dynamic';

import {AppModule} from './app/app.module';
import {environment} from './environments/environment';
import * as Sentry from '@sentry/angular-ivy';

Sentry.init({
    dsn: 'https://f4e4377bc9914840a7b747b98a127fdc@o1277317.ingest.sentry.io/6474750',
    ignoreErrors: [],
    release: environment.version,
    integrations: [
        new Sentry.BrowserTracing({
            tracingOrigins: ['localhost', 'https://photos.teamkoenig.ch/', 'https://photos.berg-turbenthal.ch/'],
            routingInstrumentation: Sentry.routingInstrumentation,

        }),
    ],

    // Set tracesSampleRate to 1.0 to capture 100%
    // of transactions for performance monitoring.
    // We recommend adjusting this value in production
    tracesSampleRate: 1.0,
    replaysOnErrorSampleRate: 1.0,
    replaysSessionSampleRate: 0.1,
});
if (environment.production) {
    enableProdMode();
}

platformBrowserDynamic().bootstrapModule(AppModule)
    .catch(err => console.log(err));
