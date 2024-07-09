use crate::data::storage::AlbumEntry;

pub trait RowIteratorTrait<'a, I: Iterator<Item=&'a AlbumEntry>> {
    fn calculate_rows(self, width: f64) -> RowIterator<'a, I>;
}

impl<'a, I: Iterator<Item=&'a AlbumEntry>> RowIteratorTrait<'a, I> for I {
    fn calculate_rows(self, width: f64) -> RowIterator<'a, I> {
        RowIterator {
            iterator: self,
            remainder: None,
            width,
        }
    }
}

pub struct RowIterator<'a, I: Iterator<Item=&'a AlbumEntry>> {
    iterator: I,
    remainder: Option<&'a AlbumEntry>,
    width: f64,
}

impl<'a, I: Iterator<Item=&'a AlbumEntry>> Iterator for RowIterator<'a, I> {
    type Item = Box<[AlbumEntry]>;

    fn next(&mut self) -> Option<Self::Item> {
        let mut row = Vec::new();
        let mut current_width = 0.0;
        if let Some(entry) = self.remainder.take() {
            current_width += entry.target_width as f64 / entry.target_height as f64;
            row.push(entry.clone());
        }
        for entry in self.iterator.by_ref() {
            current_width += entry.target_width as f64 / entry.target_height as f64;
            if !row.is_empty() && current_width > self.width {
                self.remainder = Some(entry);
                break;
            }
            row.push(entry.clone());
        }
        if row.is_empty() {
            None
        } else {
            Some(row.into_boxed_slice())
        }
    }
}
