import {AfterViewInit, Directive, TemplateRef, ViewContainerRef} from '@angular/core';


@Directive({
    selector: '[appInViewport]'
})
export class InViewportDirective implements AfterViewInit {
    alreadyRendered: boolean; // checking if visible already
    constructor(
        private vcRef: ViewContainerRef,
        private tplRef: TemplateRef<any>
    ) {
    }


    ngAfterViewInit() {
        const commentEl = this.vcRef.element.nativeElement; // template
        const elToObserve = commentEl.parentElement;
        this.setMinWidthHeight(elToObserve);
        setTimeout(() => {
            const observer = new IntersectionObserver(entries => {
                entries.forEach(entry => {
                    this.renderContents(entry.isIntersecting);
                });
            }, {rootMargin: '1000px'});
            observer.observe(elToObserve);
        }, 100);

    }

    renderContents(isInView) {
        if (isInView && !this.alreadyRendered) {
            this.vcRef.clear();
            this.vcRef.createEmbeddedView(this.tplRef);
            this.alreadyRendered = true;
        } else if (!isInView && this.alreadyRendered) {
            this.vcRef.clear();
            this.alreadyRendered = false;
        }
    }

    setMinWidthHeight(el) { // prevent issue being visible all together
        const style = window.getComputedStyle(el);
        const [width, height] = [parseInt(style.width, 10), parseInt(style.height, 10)];
        if (!width) {
            el.style.minWidth = '40px';
        }
        if (!height) {
            el.style.minHeight = '40px';
        }
    }
}
