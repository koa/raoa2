import {enableProdMode} from '@angular/core';
import {platformBrowserDynamic} from '@angular/platform-browser-dynamic';

import {AppModule} from './app/app.module';
import {environment} from './environments/environment';
import * as Sentry from '@sentry/angular';
import {BrowserTracing} from '@sentry/tracing';

Sentry.init({
    dsn: 'https://f4e4377bc9914840a7b747b98a127fdc@o1277317.ingest.sentry.io/6474750',
    ignoreErrors: ['ResizeObserver loop limit exceeded'],
    integrations: [
        new BrowserTracing({
            tracingOrigins: ['localhost', 'https://photos.teamkoenig.ch/', 'https://photos.berg-turbenthal.ch/'],
            routingInstrumentation: Sentry.routingInstrumentation,

        }),
    ],

    // Set tracesSampleRate to 1.0 to capture 100%
    // of transactions for performance monitoring.
    // We recommend adjusting this value in production
    tracesSampleRate: 1.0,
});
if (environment.production) {
    enableProdMode();
}

platformBrowserDynamic().bootstrapModule(AppModule)
    .catch(err => console.log(err));
