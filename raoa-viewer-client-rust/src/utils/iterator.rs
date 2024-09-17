use std::marker::PhantomData;

pub trait RevokedElement<E> {
    fn set(&mut self, element: E);
}
impl<E> RevokedElement<E> for &mut Option<E> {
    fn set(&mut self, element: E) {
        let _ = self.insert(element);
    }
}
pub struct IteratorSegment<
    'a,
    E,
    I: Iterator<Item = E>,
    C: SameSegmentCondition<E>,
    F: RevokedElement<E>,
> {
    first_entry: Option<E>,
    iterator: &'a mut I,
    condition: C,
    next_failed_element: Option<F>,
}

impl<'a, E, I: Iterator<Item = E>, C: SameSegmentCondition<E>, F: RevokedElement<E>>
    IteratorSegment<'a, E, I, C, F>
{
    pub fn new(
        first_entry: Option<E>,
        iterator: &'a mut I,
        condition: C,
        next_failed_element: Option<F>,
    ) -> Self {
        Self {
            first_entry,
            iterator,
            condition,
            next_failed_element,
        }
    }
}

pub trait SameSegmentCondition<E> {
    fn is_same(&self, next_entry: &E) -> bool;
}

impl<'a, E, I: Iterator<Item = E>, C: SameSegmentCondition<E>, F: RevokedElement<E>> Iterator
    for IteratorSegment<'a, E, I, C, F>
{
    type Item = E;

    fn next(&mut self) -> Option<Self::Item> {
        if let Some(first_element) = self.first_entry.take() {
            return Some(first_element);
        }
        let overflow_consumer = self.next_failed_element.as_mut()?;
        let element = self.iterator.next()?;
        if self.condition.is_same(&element) {
            Some(element)
        } else {
            overflow_consumer.set(element);
            None
        }
    }
}
pub struct SameSplitCondition<'a, D, S: Fn(&E) -> D, E> {
    discriminant_value: D,
    extractor: &'a S,
    p: PhantomData<E>,
}

impl<'a, D, S: Fn(&E) -> D, E> SameSplitCondition<'a, D, S, E> {
    pub fn new(category_entry: &E, extractor: &'a S) -> Self {
        Self {
            discriminant_value: extractor(category_entry),
            extractor,
            p: Default::default(),
        }
    }
}

impl<'a, E, D: PartialEq, S: Fn(&E) -> D> SameSegmentCondition<E>
    for SameSplitCondition<'a, D, S, E>
{
    fn is_same(&self, next_entry: &E) -> bool {
        self.discriminant_value == (self.extractor)(next_entry)
    }
}

pub struct SectionIterator<
    E,
    I: Iterator<Item = E>,
    S: Fn(&E) -> D,
    D: PartialEq,
    C: for<'a> FnMut(
        IteratorSegment<'a, E, I, SameSplitCondition<'a, D, S, E>, &'_ mut Option<E>>,
    ) -> R,
    R,
> {
    iterator: I,
    splitter: S,
    collector: C,
    last_entry: Option<E>,
    discriminant_phantom: PhantomData<D>,
}

impl<
        E,
        I: Iterator<Item = E>,
        S: Fn(&E) -> D,
        D: PartialEq,
        C: for<'a> FnMut(
            IteratorSegment<'a, E, I, SameSplitCondition<'a, D, S, E>, &'_ mut Option<E>>,
        ) -> R,
        R,
    > SectionIterator<E, I, S, D, C, R>
{
    pub fn new(iterator: I, splitter: S, collector: C) -> Self {
        Self {
            iterator,
            splitter,
            collector,
            last_entry: None,
            discriminant_phantom: Default::default(),
        }
    }
}

impl<
        E,
        I: Iterator<Item = E>,
        S: Fn(&E) -> D,
        D: PartialEq,
        C: for<'a> FnMut(
            IteratorSegment<'a, E, I, SameSplitCondition<'a, D, S, E>, &'_ mut Option<E>>,
        ) -> R,
        R,
    > Iterator for SectionIterator<E, I, S, D, C, R>
{
    type Item = R;

    fn next(&mut self) -> Option<Self::Item> {
        let mut last_entry = self.last_entry.take();
        if last_entry.is_none() {
            last_entry = self.iterator.next();
        }
        let last_entry = last_entry.take()?;
        let condition = SameSplitCondition::new(&last_entry, &self.splitter);
        Some((self.collector)(IteratorSegment::new(
            Some(last_entry),
            &mut self.iterator,
            condition,
            Some(&mut self.last_entry),
        )))
    }
}
