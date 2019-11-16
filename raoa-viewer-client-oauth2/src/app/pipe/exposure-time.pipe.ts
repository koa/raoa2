import {Pipe, PipeTransform} from '@angular/core';

@Pipe({
  name: 'exposureTime'
})
export class ExposureTimePipe implements PipeTransform {

  transform(value: number): string {
    if (value > 0.8) {
      return value.toFixed(0);
    } else {
      return '1/' + (1 / value).toFixed(0);
    }
  }

}
