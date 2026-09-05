import { bootstrapApplication } from '@angular/platform-browser';

import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';

/**
 * The entry point.
 *
 * `bootstrapApplication` is the standalone replacement for
 * `platformBrowserDynamic().bootstrapModule(AppModule)`. Recognising the older
 * line is worth as much as knowing this one, because most Angular code you meet
 * will still have it.
 */
bootstrapApplication(AppComponent, appConfig).catch((err) => console.error(err));
