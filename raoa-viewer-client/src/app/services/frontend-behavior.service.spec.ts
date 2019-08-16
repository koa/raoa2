import {TestBed} from '@angular/core/testing';

import {FrontendBehaviorService} from './frontend-behavior.service';

describe('FrontendBehaviorService', () => {
  beforeEach(() => TestBed.configureTestingModule({}));

  it('should be created', () => {
    const service: FrontendBehaviorService = TestBed.get(FrontendBehaviorService);
    expect(service).toBeTruthy();
  });
});
