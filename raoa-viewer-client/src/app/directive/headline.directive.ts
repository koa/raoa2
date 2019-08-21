import {Directive, ViewContainerRef} from '@angular/core';

@Directive({
  selector: '[appHeadline]'
})
export class HeadlineDirective {

  constructor(public viewContainerRef: ViewContainerRef) {
  }

}
