import { TestBed } from '@angular/core/testing';

import { SvpsDataService } from './svps-data.service';

describe('SvpsDataService', () => {
  let service: SvpsDataService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(SvpsDataService);
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });
});
