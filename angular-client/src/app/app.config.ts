import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { correlationInterceptor } from './interceptors/correlation.interceptor';

/**
 * ============================================================================
 * THE APPLICATION CONFIGURATION - Angular answer to a Spring @Configuration
 * ============================================================================
 * Standalone Angular has no root NgModule. This object is what replaces it: a
 * list of providers for the root injector.
 *
 * <pre>
 *   Spring                              Angular
 *   ---------------------------------   ---------------------------------
 *   {@literal @}Configuration class                ApplicationConfig object
 *   {@literal @}Bean method                        a provideXxx() function
 *   the root ApplicationContext         the root injector
 * </pre>
 *
 * provideHttpClient() is why CourseService can inject HttpClient at all.
 * FORGETTING IT is the classic standalone-Angular startup failure: a
 * NullInjectorError naming HttpClient, which reads like a missing import rather
 * than a missing provider. It is the same class of error as forgetting
 * spring-boot-starter-validation and wondering why @Valid does nothing.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    // eventCoalescing batches change detection for events that fire in bursts.
    // A micro-optimisation, and the default in generated projects.
    provideZoneChangeDetection({ eventCoalescing: true }),

    provideHttpClient(withInterceptors([correlationInterceptor])),
  ],
};
